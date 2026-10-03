// SPDX-License-Identifier: Apache-2.0
package dev.causeline.test;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The traces recorded since the current test started. Obtained as a test method parameter in a
 * class annotated with {@link CauselineTest}.
 */
public final class RecordedTraces {

    private static final long POLL_MILLIS = 20;

    private final TraceStore store;
    private final Runnable flush;
    private final Set<String> ignored = new HashSet<>();
    private Duration timeout = Duration.ofSeconds(5);

    RecordedTraces(TraceStore store, Runnable flush) {
        this.store = store;
        this.flush = flush;
        reset();
    }

    /** How long to wait for a trace to arrive and settle. Five seconds unless changed. */
    public RecordedTraces within(Duration limit) {
        this.timeout = limit;
        return this;
    }

    /**
     * Forgets everything recorded so far. Call it after set-up requests, so that only what follows
     * is asserted on.
     */
    public void reset() {
        flush.run();
        store.snapshot().forEach(trace -> ignored.add(trace.getFirst().traceId()));
    }

    /** The one trace recorded since the test started (or since {@link #reset()}); fails when there are none or several. */
    public TraceAssert trace() {
        List<TraceAssert> traces = traces();
        if (traces.size() != 1) {
            throw new AssertionError(traces.isEmpty() ? nothingRecorded()
                    : "Expected one trace but " + traces.size() + " were recorded: " + names(traces)
                            + ". Pick one with trace(\"<request or root span name>\").");
        }
        return traces.getFirst();
    }

    /** The one recorded trace containing a span with this name, for example {@code "POST /api/orders"}. */
    public TraceAssert trace(String spanName) {
        List<TraceAssert> all = traces();
        List<TraceAssert> matching = all.stream().filter(t -> t.contains(spanName)).toList();
        if (matching.size() != 1) {
            throw new AssertionError(all.isEmpty() ? nothingRecorded()
                    : "Expected one trace with a span named \"" + spanName + "\" but found " + matching.size()
                            + " among: " + names(all));
        }
        return matching.getFirst();
    }

    /** Every trace recorded since the test started, oldest first. Empty when nothing arrived in time. */
    public List<TraceAssert> traces() {
        return await().stream().map(TraceAssert::new).toList();
    }

    /**
     * Waits until the new traces look finished: each has its root or server request span, and no
     * span arrived between two looks. Spans are stored when they end, children before parents, so
     * a trace without its root is still running.
     */
    private List<List<Span>> await() {
        long deadline = System.nanoTime() + timeout.toNanos();
        int lastCount = -1;
        while (true) {
            flush.run();
            List<List<Span>> fresh = store.snapshot().stream()
                    .filter(trace -> !ignored.contains(trace.getFirst().traceId()))
                    .sorted(Comparator.comparingLong(RecordedTraces::startOf))
                    .toList();
            int count = fresh.stream().mapToInt(List::size).sum();
            boolean settled = !fresh.isEmpty() && count == lastCount && fresh.stream().allMatch(RecordedTraces::hasRoot);
            if (settled || System.nanoTime() >= deadline) {
                return fresh;
            }
            lastCount = count;
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fresh;
            }
        }
    }

    private static boolean hasRoot(List<Span> trace) {
        return trace.stream().anyMatch(span -> !span.hasParent() || span.kind() == SpanKind.REQUEST);
    }

    private static long startOf(List<Span> trace) {
        return trace.stream().mapToLong(Span::startTimeUnixNano).min().orElse(0);
    }

    private String nothingRecorded() {
        return "No trace was recorded within " + timeout.toMillis() + " ms. The test has to go through something "
                + "Causeline traces: an HTTP request (MockMvc, WebTestClient or a real port) or an @Observed method.";
    }

    private static List<String> names(List<TraceAssert> traces) {
        return traces.stream().map(TraceAssert::name).toList();
    }
}
