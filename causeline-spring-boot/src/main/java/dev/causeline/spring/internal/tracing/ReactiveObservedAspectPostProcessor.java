// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservationKeyValueAnnotationHandler;
import io.micrometer.observation.aop.ObservedAspect;
import java.lang.reflect.Field;
import java.util.function.Predicate;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Makes Micrometer's {@link ObservedAspect} leave methods that return a {@code Mono} or
 * {@code Flux} to {@link ReactiveObservedAspect}. The aspect is rebuilt with the same registry,
 * convention, skip rule and key-value handler, plus "skip reactive methods"; ObservedAspect offers
 * no other way to add a skip rule after it is built.
 *
 * <p>Only Micrometer's own class is replaced, never a subclass the application defined. If the
 * rebuild fails, the original is kept and reactive methods get both spans, which is still correct.
 */
public final class ReactiveObservedAspectPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReactiveObservedAspectPostProcessor.class);

    @Override
    @SuppressWarnings("unchecked")
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean.getClass() != ObservedAspect.class) {
            return bean;
        }
        try {
            ObservationRegistry registry = (ObservationRegistry) read(bean, "registry");
            var convention = (ObservationConvention<ObservedAspect.ObservedAspectContext>) read(bean, "observationConvention");
            Predicate<ProceedingJoinPoint> skip = (Predicate<ProceedingJoinPoint>) read(bean, "shouldSkip");
            var handler = (ObservationKeyValueAnnotationHandler) read(bean, "observationKeyValueAnnotationHandler");
            Predicate<ProceedingJoinPoint> skipReactive = pjp -> pjp.getSignature() instanceof MethodSignature signature
                    && ReactiveSupport.returnsReactive(signature.getMethod());
            ObservedAspect replacement = new ObservedAspect(registry, convention,
                    skip == null ? skipReactive : skip.or(skipReactive));
            if (handler != null) {
                replacement.setObservationKeyValueAnnotationHandler(handler);
            }
            return replacement;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("Causeline could not make ObservedAspect skip reactive methods; they get two spans", e);
            return bean;
        }
    }

    private static Object read(Object bean, String field) throws ReflectiveOperationException {
        Field f = ObservedAspect.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(bean);
    }
}
