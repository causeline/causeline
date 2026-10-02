// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;

/**
 * Adds arguments and the return value to the spans of {@code @Observed} methods. Micrometer's
 * {@link ObservedAspect} creates those spans; this aspect runs inside it (lowest precedence), finds
 * the observation it opened for this very call, and records the values before it closes.
 *
 * <p>If it ever ran outside instead (another aspect ordering), the current observation would not
 * belong to this call, and nothing is recorded rather than putting values on the wrong span.
 */
@Aspect
public final class ObservedValuesAspect implements Ordered {

    private final ObservationRegistry registry;
    private final MethodValues values;

    public ObservedValuesAspect(ObservationRegistry registry, MethodValues values) {
        this.registry = registry;
        this.values = values;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Around("(@annotation(io.micrometer.observation.annotation.Observed)"
            + " || @within(io.micrometer.observation.annotation.Observed)) && !within(dev.causeline.spring..*)")
    public Object record(ProceedingJoinPoint pjp) throws Throwable {
        Observation observation = registry.getCurrentObservation();
        if (observation == null || !isFor(observation, pjp)) {
            return pjp.proceed();
        }
        values.arguments(observation, pjp);
        Object result = pjp.proceed();
        values.returned(observation, pjp, result);
        return result;
    }

    /** The observation ObservedAspect opened for this same method call. */
    private static boolean isFor(Observation observation, ProceedingJoinPoint pjp) {
        // Each aspect gets its own join point and signature objects, so compare what they point at.
        return observation.getContext() instanceof ObservedAspect.ObservedAspectContext context
                && context.getProceedingJoinPoint().getSignature() instanceof MethodSignature theirs
                && pjp.getSignature() instanceof MethodSignature ours
                && theirs.getMethod().equals(ours.getMethod())
                && context.getProceedingJoinPoint().getTarget() == pjp.getTarget();
    }
}
