// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.core.SpanKind;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.aop.support.AopUtils;
import org.springframework.data.repository.Repository;
import org.springframework.util.ClassUtils;

/**
 * Adds a {@link SpanKind#REPOSITORY} span around Spring Data repository calls, named after the
 * application's repository interface (e.g. {@code OrderRepository.save}) rather than the
 * framework interface that declares the method.
 */
@Aspect
public final class RepositoryObservationAspect implements Ordered {

    /** Just outside the innermost aspects (a paused replay's), so they run inside this span. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }


    private final ObservationRegistry registry;
    private final MethodValues values;

    public RepositoryObservationAspect(ObservationRegistry registry) {
        this(registry, MethodValues.OFF);
    }

    /** @param values records the method's arguments and return value on the span */
    public RepositoryObservationAspect(ObservationRegistry registry, MethodValues values) {
        this.registry = registry;
        this.values = values;
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))"
            + " && !execution(* java.lang.Object.*(..))")
    public Object observe(ProceedingJoinPoint pjp) throws Throwable {
        // The proxy (getThis) implements the application's interface; the target is Spring's SimpleJpaRepository.
        Class<?> repository = repositoryInterface(pjp.getThis());
        String name = (repository == null ? AopUtils.getTargetClass(pjp.getThis()).getSimpleName()
                : repository.getSimpleName()) + "." + pjp.getSignature().getName();
        Observation observation = Observation.createNotStarted("causeline.repository", registry)
                .contextualName(name)
                .lowCardinalityKeyValue(SpanMapper.KIND_ATTRIBUTE, SpanKind.REPOSITORY.name())
                .lowCardinalityKeyValue("method", pjp.getSignature().getName());
        if (repository != null) {
            observation.lowCardinalityKeyValue("class", repository.getName());
        }
        return MethodSpans.observe(observation, pjp, values);
    }

    static String repositoryName(Object proxy) {
        Class<?> repository = repositoryInterface(proxy);
        return repository != null ? repository.getSimpleName() : AopUtils.getTargetClass(proxy).getSimpleName();
    }

    /** The application's repository interface, or null when only Spring's own are implemented. */
    static Class<?> repositoryInterface(Object proxy) {
        return ClassUtils.getAllInterfacesAsSet(proxy).stream()
                .filter(Repository.class::isAssignableFrom)
                .filter(i -> !i.getName().startsWith("org.springframework."))
                .findFirst()
                .orElse(null);
    }
}
