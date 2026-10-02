// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.autoconfigure;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.internal.capture.RequestCaptureFilter;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.export.OtlpForwarder;
import dev.causeline.spring.internal.export.SpanForwarder;
import dev.causeline.spring.internal.export.TracePersistence;
import dev.causeline.spring.internal.export.UpstreamForwarder;
import dev.causeline.spring.internal.tracing.CauselineSpanExporter;
import dev.causeline.spring.internal.tracing.CauselineSpanProcessor;
import dev.causeline.spring.internal.tracing.CauselineStats;
import dev.causeline.spring.internal.capture.ValueRenderer;
import dev.causeline.spring.internal.tracing.ControllerObservationAspect;
import dev.causeline.spring.internal.tracing.MethodValues;
import dev.causeline.spring.internal.tracing.ObservedValuesAspect;
import dev.causeline.spring.internal.tracing.ExceptionSpans;
import dev.causeline.spring.internal.tracing.LoggedExceptionAppender;
import dev.causeline.spring.internal.tracing.LoggedExceptionCapture;
import dev.causeline.spring.internal.tracing.RepositoryObservationAspect;
import dev.causeline.spring.internal.tracing.SpanMapper;
import dev.causeline.spring.internal.web.CauselineReplayConfiguration;
import dev.causeline.spring.internal.web.CauselineWebConfiguration;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Entry point for Causeline. Everything Causeline registers hangs off this configuration,
 * so nothing is active unless {@link OnCauselineEnabledCondition} matches.
 */
@AutoConfiguration(
        beforeName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
        afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@Conditional(OnCauselineEnabledCondition.class)
@EnableConfigurationProperties(CauselineProperties.class)
@Import({CauselineWebConfiguration.class, CauselineReplayConfiguration.class})
public class CauselineAutoConfiguration {

    /** How often finished spans move from the batch queue into the store. */
    static final Duration EXPORT_DELAY = Duration.ofMillis(200);
    static final int EXPORT_QUEUE_CAPACITY = 2048;
    private static final Logger log = LoggerFactory.getLogger(CauselineAutoConfiguration.class);
    static final Duration OTLP_INTERVAL = Duration.ofSeconds(1);
    static final Duration PERSIST_INTERVAL = Duration.ofSeconds(30);

    @Bean
    TraceStore causelineTraceStore(CauselineProperties properties) {
        return new TraceStore(properties.store().maxTraces(), properties.store().maxSize().toBytes());
    }

    @Bean
    CauselineStats causelineStats() {
        return new CauselineStats();
    }

    @Bean
    SpanRedactor causelineExportRedactor(CauselineProperties properties) {
        return properties.export().redactSecrets()
                ? new SpanRedactor(SensitiveData.forExport(properties.capture().redactKeys()))
                : SpanRedactor.NONE;
    }

    /** Opt-in request details (headers, query, raw path), filtered before they reach a span. */
    @Bean
    @ConditionalOnClass(name = "org.springframework.http.server.observation.ServerRequestObservationContext")
    RequestCaptureFilter causelineRequestCaptureFilter(CauselineProperties properties) {
        return new RequestCaptureFilter(properties.capture(), SensitiveData.userBlocked(properties.capture().redactKeys()));
    }

    /** OTLP export of the spans Causeline stores; off unless causeline.export.otlp.endpoint is set. */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${causeline.export.otlp.endpoint:}' != ''")
    OtlpForwarder causelineOtlpForwarder(CauselineProperties properties, Environment environment, CauselineStats stats,
            SpanRedactor redactor) {
        return new OtlpForwarder(properties.export().otlp(), source(environment), stats, OTLP_INTERVAL, redactor);
    }

    /** Sends this service's spans to the calling service's Causeline; off unless causeline.export.upstream.url is set. */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${causeline.export.upstream.url:}' != ''")
    UpstreamForwarder causelineUpstreamForwarder(CauselineProperties properties, CauselineStats stats,
            SpanRedactor redactor) {
        return new UpstreamForwarder(properties.export().upstream(), stats, OTLP_INTERVAL, redactor);
    }

    /**
     * Carries the current trace into {@code @Async} methods and Spring's task executor, so work
     * handed to another thread stays in the request's trace instead of starting a new one. Spring
     * Boot applies a single {@code TaskDecorator} bean to the executors it configures; an
     * application that defines its own keeps it.
     */
    @Bean
    @ConditionalOnClass(name = "io.micrometer.context.ContextSnapshotFactory")
    @ConditionalOnMissingBean(org.springframework.core.task.TaskDecorator.class)
    org.springframework.core.task.TaskDecorator causelineContextPropagation() {
        return new org.springframework.core.task.support.ContextPropagatingTaskDecorator();
    }

    /** Keeps traces across restarts; off unless causeline.store.persist=true. Loads the last run's traces first. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "causeline.store", name = "persist", havingValue = "true")
    TracePersistence causelineTracePersistence(TraceStore store, CauselineProperties properties, Environment environment,
            SpanRedactor redactor) {
        String app = source(environment);
        String dir = properties.store().directory();
        java.nio.file.Path directory = dir == null || dir.isBlank()
                ? java.nio.file.Path.of(System.getProperty("user.home"), ".causeline", app.replaceAll("[^A-Za-z0-9._-]", "_"))
                : java.nio.file.Path.of(dir);
        TracePersistence persistence = new TracePersistence(store, directory, redactor, app, PERSIST_INTERVAL);
        int loaded = persistence.load();
        if (loaded > 0) {
            log.info("Causeline loaded {} spans saved by the previous run from {}", loaded, persistence.file());
        }
        return persistence;
    }

    /** Every stored span goes to each configured export: OTLP and the upstream Causeline. */
    @Bean
    SpanForwarder causelineForwarder(ObjectProvider<OtlpForwarder> otlp, ObjectProvider<UpstreamForwarder> upstream) {
        List<SpanForwarder> forwarders = new java.util.ArrayList<>();
        otlp.ifAvailable(forwarders::add);
        upstream.ifAvailable(forwarders::add);
        return SpanForwarder.all(forwarders);
    }

    @Bean
    ExceptionSpans causelineExceptionSpans(CauselineProperties properties, BeanFactory beanFactory) {
        List<String> appPackages = AutoConfigurationPackages.has(beanFactory)
                ? AutoConfigurationPackages.get(beanFactory)
                : List.of();
        return new ExceptionSpans(properties.capture().exceptionDetails(), appPackages);
    }

    /**
     * Picked up by Spring Boot's OpenTelemetry tracer. Its bounded, drop-and-count queue keeps
     * span export off request threads.
     */
    @Bean
    SpanProcessor causelineSpanProcessor(TraceStore store, Environment environment, ExceptionSpans exceptions,
            CauselineProperties properties, CauselineStats stats, @Qualifier("causelineForwarder") SpanForwarder causelineForwarder) {
        SpanForwarder forwarder = causelineForwarder;
        SpanMapper mapper = new SpanMapper(source(environment), properties.capture().sql());
        return new CauselineSpanProcessor(new CauselineSpanExporter(mapper, exceptions, store, forwarder, stats),
                EXPORT_QUEUE_CAPACITY, EXPORT_DELAY, stats);
    }

    /** Arguments and return values of controller, service and repository methods (causeline.capture.arguments). */
    @Bean
    MethodValues causelineMethodValues(CauselineProperties properties) {
        return properties.capture().arguments()
                ? new MethodValues(new ValueRenderer(SensitiveData.userBlocked(properties.capture().redactKeys())))
                : MethodValues.OFF;
    }

    /** Adds those values to the spans of @Observed services, which Micrometer's own aspect creates. */
    @Bean
    @ConditionalOnClass(name = "io.micrometer.observation.aop.ObservedAspect")
    @ConditionalOnProperty(prefix = "causeline.capture", name = "arguments", havingValue = "true", matchIfMissing = true)
    ObservedValuesAspect causelineObservedValuesAspect(ObservationRegistry registry, MethodValues values) {
        return new ObservedValuesAspect(registry, values);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    ControllerObservationAspect causelineControllerObservationAspect(ObservationRegistry registry, MethodValues values) {
        return new ControllerObservationAspect(registry, values);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.repository.Repository")
    static class RepositoryTracing {

        @Bean
        RepositoryObservationAspect causelineRepositoryObservationAspect(ObservationRegistry registry,
                MethodValues values) {
            return new RepositoryObservationAspect(registry, values);
        }
    }

    /** Surfaces exceptions the application catches and logs, when Logback is the logging backend. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    static class LoggedExceptions {

        @Bean
        LoggedExceptionCapture causelineLoggedExceptionCapture(ObjectProvider<Tracer> tracer, ExceptionSpans exceptions,
                TraceStore store, Environment environment) {
            Supplier<LoggedExceptionAppender.ActiveSpan> activeSpan = () -> {
                // Resolved per event: the tracer is created after Causeline's configuration.
                Tracer current = tracer.getIfAvailable();
                io.micrometer.tracing.Span span = current == null ? null : current.currentSpan();
                return span == null ? null
                        : new LoggedExceptionAppender.ActiveSpan(span.context().traceId(), span.context().spanId());
            };
            return new LoggedExceptionCapture(
                    new LoggedExceptionAppender(activeSpan, exceptions, store, source(environment)));
        }
    }

    static String source(Environment environment) {
        return environment.getProperty("spring.application.name", "spring");
    }
}
