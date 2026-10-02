// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import java.lang.reflect.Method;
import org.springframework.util.ClassUtils;

/**
 * Whether a method returns a Reactor {@code Mono} or {@code Flux}. Safe to call without Reactor on
 * the classpath; {@link ReactorResults}, which needs Reactor, is only touched when this says yes.
 */
final class ReactiveSupport {

    private static final Class<?> MONO = load("reactor.core.publisher.Mono");
    private static final Class<?> FLUX = load("reactor.core.publisher.Flux");

    private ReactiveSupport() {
    }

    static boolean returnsReactive(Method method) {
        Class<?> type = method.getReturnType();
        return (MONO != null && MONO.isAssignableFrom(type)) || (FLUX != null && FLUX.isAssignableFrom(type));
    }

    private static Class<?> load(String name) {
        ClassLoader loader = ReactiveSupport.class.getClassLoader();
        return ClassUtils.isPresent(name, loader) ? ClassUtils.resolveClassName(name, loader) : null;
    }
}
