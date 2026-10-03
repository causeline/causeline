// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.core.SpanKind;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * {@link SpanKind#CACHE} spans for Spring Cache: each lookup (hit or miss), write and eviction made
 * during a traced request, with the cache name and key.
 *
 * <p>Every {@link CacheManager} bean is wrapped in a subclass proxy, so it keeps its type for
 * injection, and the caches it hands out record what happens to them. Outside a traced request
 * nothing is recorded.
 */
public final class CacheSpans implements BeanPostProcessor {

    static final String HIT = "cache.hit";

    private final Supplier<ObservationRegistry> registry;
    private final Supplier<MethodValues> values;

    /**
     * Both are looked up when first needed: a post-processor must not create beans early.
     *
     * @param values renders keys as JSON, as arguments are (nothing when argument capture is off)
     */
    public CacheSpans(Supplier<ObservationRegistry> registry, Supplier<MethodValues> values) {
        this.registry = Once.of(registry);
        this.values = Once.of(values);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof CacheManager)) {
            return bean;
        }
        try {
            ProxyFactory factory = new ProxyFactory(bean);
            factory.setProxyTargetClass(true);
            factory.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                return result instanceof Cache cache && invocation.getMethod().getName().equals("getCache")
                        ? new TracedCache(cache)
                        : result;
            });
            return factory.getProxy(bean.getClass().getClassLoader());
        } catch (RuntimeException e) {
            return bean; // a final class or similar: no cache spans, nothing else changes
        }
    }

    private Observation start(String operation, Cache cache, Object key) {
        ObservationRegistry observations = registry.get();
        if (observations == null || observations.getCurrentObservation() == null) {
            return null;
        }
        Observation observation = Observation.createNotStarted("causeline.cache", observations)
                .contextualName(operation + " " + cache.getName())
                .lowCardinalityKeyValue(SpanMapper.KIND_ATTRIBUTE, SpanKind.CACHE.name())
                .lowCardinalityKeyValue("cache.name", cache.getName())
                .lowCardinalityKeyValue("cache.operation", operation);
        MethodValues renderer = values.get();
        String rendered = key == null || renderer == null ? null : renderer.render(key);
        if (rendered != null) {
            observation.highCardinalityKeyValue("cache.key", rendered);
        }
        return observation.start();
    }

    private static void stop(Observation observation, Boolean hit) {
        if (observation == null) {
            return;
        }
        // Hit or miss is an attribute, not part of the name, so runs of the same action still line up when compared.
        if (hit != null) {
            observation.lowCardinalityKeyValue(HIT, hit.toString());
        }
        observation.stop();
    }

    /** A cache that records each operation; everything else is passed through unchanged. */
    private final class TracedCache implements Cache {

        private final Cache delegate;

        TracedCache(Cache delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            Observation observation = start("GET", delegate, key);
            ValueWrapper value = null;
            try {
                value = delegate.get(key);
                return value;
            } finally {
                stop(observation, value != null);
            }
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            Observation observation = start("GET", delegate, key);
            T value = null;
            try {
                value = delegate.get(key, type);
                return value;
            } finally {
                stop(observation, value != null);
            }
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            Observation observation = start("GET", delegate, key);
            AtomicBoolean loaded = new AtomicBoolean();
            try {
                return delegate.get(key, () -> {
                    loaded.set(true);
                    // The loader is the cached method itself: its spans belong under the cache miss.
                    if (observation == null) {
                        return valueLoader.call();
                    }
                    try (Observation.Scope scope = observation.openScope()) {
                        return valueLoader.call();
                    }
                });
            } finally {
                stop(observation, !loaded.get());
            }
        }

        @Override
        public CompletableFuture<?> retrieve(Object key) {
            Observation observation = start("GET", delegate, key);
            CompletableFuture<?> future = delegate.retrieve(key);
            if (future == null) {
                stop(observation, false);
                return null;
            }
            return future.whenComplete((value, error) -> stop(observation, value != null));
        }

        @Override
        public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
            Observation observation = start("GET", delegate, key);
            AtomicBoolean loaded = new AtomicBoolean();
            return delegate.retrieve(key, () -> {
                loaded.set(true);
                return valueLoader.get();
            }).whenComplete((value, error) -> stop(observation, !loaded.get()));
        }

        @Override
        public void put(Object key, Object value) {
            Observation observation = start("PUT", delegate, key);
            try {
                delegate.put(key, value);
            } finally {
                stop(observation, null);
            }
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            Observation observation = start("PUT", delegate, key);
            try {
                return delegate.putIfAbsent(key, value);
            } finally {
                stop(observation, null);
            }
        }

        @Override
        public void evict(Object key) {
            Observation observation = start("EVICT", delegate, key);
            try {
                delegate.evict(key);
            } finally {
                stop(observation, null);
            }
        }

        @Override
        public boolean evictIfPresent(Object key) {
            Observation observation = start("EVICT", delegate, key);
            try {
                return delegate.evictIfPresent(key);
            } finally {
                stop(observation, null);
            }
        }

        @Override
        public void clear() {
            Observation observation = start("CLEAR", delegate, null);
            try {
                delegate.clear();
            } finally {
                stop(observation, null);
            }
        }

        @Override
        public boolean invalidate() {
            Observation observation = start("CLEAR", delegate, null);
            try {
                return delegate.invalidate();
            } finally {
                stop(observation, null);
            }
        }
    }
}
