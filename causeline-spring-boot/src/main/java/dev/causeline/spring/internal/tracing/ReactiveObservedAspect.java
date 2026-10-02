// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.observation.aop.ObservedAspect;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Spans for {@code @Observed} methods that return a {@code Mono} or {@code Flux}. Micrometer's
 * {@link ObservedAspect} closes its span when the method returns, which for reactive code is before
 * the work runs; {@link ReactiveObservedAspectPostProcessor} makes it skip those methods, and this
 * aspect observes them instead, with the same name, contextual name and tags Micrometer would use,
 * keeping the span open until the result finishes.
 */
@Aspect
public final class ReactiveObservedAspect implements Ordered {

    static final String DEFAULT_NAME = "method.observed";

    private final ObservationRegistry registry;
    private final MethodValues values;

    public ReactiveObservedAspect(ObservationRegistry registry, MethodValues values) {
        this.registry = registry;
        this.values = values;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Around("(@annotation(io.micrometer.observation.annotation.Observed)"
            + " || @within(io.micrometer.observation.annotation.Observed)) && !within(dev.causeline.spring..*)")
    public Object observe(ProceedingJoinPoint pjp) throws Throwable {
        if (!(pjp.getSignature() instanceof MethodSignature signature)
                || !ReactiveSupport.returnsReactive(signature.getMethod())) {
            return pjp.proceed();
        }
        Method method = signature.getMethod();
        Class<?> type = pjp.getTarget() == null ? method.getDeclaringClass() : pjp.getTarget().getClass();
        Observed observed = AnnotatedElementUtils.findMergedAnnotation(method, Observed.class);
        if (observed == null) {
            observed = AnnotatedElementUtils.findMergedAnnotation(type, Observed.class);
        }
        String name = observed == null || observed.name().isBlank() ? DEFAULT_NAME : observed.name();
        String contextualName = observed == null || observed.contextualName().isBlank()
                ? type.getSimpleName() + "#" + method.getName()
                : observed.contextualName();
        Observation observation = Observation
                .createNotStarted(name, () -> new ObservedAspect.ObservedAspectContext(pjp), registry)
                .contextualName(contextualName)
                .lowCardinalityKeyValue("class", type.getName())
                .lowCardinalityKeyValue("method", method.getName());
        if (observed != null) {
            String[] pairs = observed.lowCardinalityKeyValues();
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                observation.lowCardinalityKeyValue(pairs[i], pairs[i + 1]);
            }
        }
        return MethodSpans.observe(observation, pjp, values);
    }
}
