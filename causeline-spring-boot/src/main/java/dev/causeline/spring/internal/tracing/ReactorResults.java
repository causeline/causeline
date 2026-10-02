// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps a method's span open until the {@code Mono} or {@code Flux} it returned finishes, instead of
 * closing it when the method returns (which, for reactive code, is before any work has happened).
 * The span is also made the current observation for the operators the method assembled, so the
 * queries and calls they make become its children.
 */
final class ReactorResults {

    /** At most this many items of a Flux are kept to show as its return value. */
    static final int MAX_ITEMS = 25;

    private ReactorResults() {
    }

    /**
     * @param observation already started; stopped exactly once, when the result completes, fails or
     *                    is cancelled
     * @param returned    receives what the result produced: a Mono's value (null when empty) or the
     *                    first {@value #MAX_ITEMS} items of a Flux
     */
    static Object finishWhenDone(Object result, Observation observation, Consumer<Object> returned) {
        AtomicBoolean done = new AtomicBoolean();
        Runnable stop = () -> {
            if (done.compareAndSet(false, true)) {
                observation.stop();
            }
        };
        if (result instanceof Mono<?> mono) {
            return mono
                    .doOnSuccess(returned::accept)
                    .doOnError(observation::error)
                    // Before the result reaches the caller, so the span ends when the work does.
                    .doOnTerminate(stop)
                    .doOnCancel(stop)
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        }
        if (result instanceof Flux<?> flux) {
            List<Object> items = new ArrayList<>();
            return flux
                    .doOnNext(item -> {
                        synchronized (items) {
                            if (items.size() < MAX_ITEMS) {
                                items.add(item);
                            }
                        }
                    })
                    .doOnError(observation::error)
                    .doOnComplete(() -> {
                        synchronized (items) {
                            returned.accept(List.copyOf(items));
                        }
                    })
                    .doOnTerminate(stop)
                    .doOnCancel(stop)
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        }
        stop.run();
        return result;
    }
}
