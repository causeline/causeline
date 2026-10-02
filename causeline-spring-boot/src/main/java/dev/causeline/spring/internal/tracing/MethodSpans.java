// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import io.micrometer.observation.Observation;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * Runs a method inside its span, recording arguments and the return value. A span for a method
 * that returns a {@code Mono} or {@code Flux} lasts until that result finishes, not until the
 * method returns it.
 */
final class MethodSpans {

    private MethodSpans() {
    }

    static Object observe(Observation observation, ProceedingJoinPoint pjp, MethodValues values) throws Throwable {
        values.arguments(observation, pjp);
        Method method = pjp.getSignature() instanceof MethodSignature signature ? signature.getMethod() : null;
        if (method == null || !ReactiveSupport.returnsReactive(method)) {
            return observation.observeChecked((Observation.CheckedCallable<Object, Throwable>) () -> {
                Object result = pjp.proceed();
                values.returned(observation, pjp, result);
                return result;
            });
        }
        observation.start();
        Object result;
        // The scope covers assembly, so spans the method starts while building its result are children.
        try (Observation.Scope scope = observation.openScope()) {
            result = pjp.proceed();
        } catch (Throwable e) {
            observation.error(e);
            observation.stop();
            throw e;
        }
        if (result == null) {
            observation.stop();
            return null;
        }
        return ReactorResults.finishWhenDone(result, observation, value -> values.produced(observation, value));
    }
}
