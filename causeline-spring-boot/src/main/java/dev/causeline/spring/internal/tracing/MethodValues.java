// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.spring.internal.capture.ValueRenderer;
import io.micrometer.observation.Observation;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * Records a method's arguments and return value on its span ({@code causeline.capture.arguments}),
 * so the trace shows what each layer received and handed back, not only the HTTP body.
 */
public final class MethodValues {

    public static final String ARGUMENTS = "causeline.arguments";
    public static final String RETURNED = "causeline.return";

    /** Records nothing: {@code causeline.capture.arguments=false}. */
    public static final MethodValues OFF = new MethodValues(null);

    private final ValueRenderer renderer;

    public MethodValues(ValueRenderer renderer) {
        this.renderer = renderer;
    }

    public boolean enabled() {
        return renderer != null;
    }

    /** Any value as JSON, under the same rules as arguments; null when argument capture is off. */
    public String render(Object value) {
        return renderer == null ? null : renderer.value(value);
    }

    public void arguments(Observation observation, ProceedingJoinPoint pjp) {
        if (renderer == null || !(pjp.getSignature() instanceof MethodSignature signature)) {
            return;
        }
        String json = renderer.arguments(signature.getMethod(), pjp.getArgs());
        if (json != null) {
            observation.highCardinalityKeyValue(ARGUMENTS, json);
        }
    }

    public void returned(Observation observation, ProceedingJoinPoint pjp, Object value) {
        if (renderer == null || !(pjp.getSignature() instanceof MethodSignature signature)) {
            return;
        }
        Method method = signature.getMethod();
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
            return;
        }
        observation.highCardinalityKeyValue(RETURNED, renderer.value(value));
    }

    /**
     * What a {@code Mono} or {@code Flux} produced, recorded when it finishes. Nothing is recorded
     * for an empty {@code Mono}, which is also what {@code Mono<Void>} always is.
     */
    public void produced(Observation observation, Object value) {
        if (renderer == null || value == null) {
            return;
        }
        observation.highCardinalityKeyValue(RETURNED, renderer.value(value));
    }
}
