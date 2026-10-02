// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.core.SpanKind;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * Adds a {@link SpanKind#CONTROLLER} span around each controller method, below the HTTP server
 * span Spring already records. Causeline's own endpoints are excluded.
 */
@Aspect
public final class ControllerObservationAspect {

    private final ObservationRegistry registry;
    private final MethodValues values;

    public ControllerObservationAspect(ObservationRegistry registry) {
        this(registry, MethodValues.OFF);
    }

    /** @param values records the method's arguments and return value on the span */
    public ControllerObservationAspect(ObservationRegistry registry, MethodValues values) {
        this.registry = registry;
        this.values = values;
    }

    @Around("(@within(org.springframework.web.bind.annotation.RestController)"
            + " || @within(org.springframework.stereotype.Controller))"
            + " && !within(dev.causeline.spring..*)")
    public Object observe(ProceedingJoinPoint pjp) throws Throwable {
        // Only inside an HTTP request that is being traced. Error dispatches (/error after a 500)
        // run after the request's span has closed and would otherwise start orphan traces.
        if (registry.getCurrentObservation() == null) {
            return pjp.proceed();
        }
        String name = pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName();
        Observation observation = Observation.createNotStarted("causeline.controller", registry)
                .contextualName(name)
                .lowCardinalityKeyValue(SpanMapper.KIND_ATTRIBUTE, SpanKind.CONTROLLER.name());
        return MethodSpans.observe(observation, pjp, values);
    }
}
