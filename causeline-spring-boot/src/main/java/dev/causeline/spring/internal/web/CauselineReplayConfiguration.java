// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.replay.BodyRedactor;
import dev.causeline.spring.ReplayAuthProvider;
import dev.causeline.spring.internal.replay.ReplayCaptureFilter;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplayStore;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Request capture, replay and comparison (milestone 0.3). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
public class CauselineReplayConfiguration {

    /** Inside Spring's HTTP observation filter (HIGHEST_PRECEDENCE + 1), so the request's span is current. */
    static final int CAPTURE_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Bean
    ReplayStore causelineReplayStore(CauselineProperties properties) {
        return new ReplayStore(properties.store().maxTraces());
    }

    @Bean
    FilterRegistrationBean<ReplayCaptureFilter> causelineReplayCaptureFilter(ReplayStore store,
            CauselineProperties properties, ObjectProvider<Tracer> tracer, ObjectProvider<ObservationRegistry> registry) {
        ReplayCaptureFilter.ActiveSpan activeSpan = () -> {
            Tracer current = tracer.getIfAvailable();
            io.micrometer.tracing.Span span = current == null ? null : current.currentSpan();
            return span == null ? null : new String[] {span.context().traceId(), span.context().spanId()};
        };
        BodyRedactor userRedactor = CauselineWebSupport.userBodyRedactor(properties);
        Set<String> blockedHeaders = CauselineWebSupport.blockedHeaders(properties);
        ReplayCaptureFilter filter = new ReplayCaptureFilter(store, properties.capture().requestBody(),
                properties.capture().responseBody(), userRedactor,
                activeSpan, () -> {
                    ObservationRegistry current = registry.getIfAvailable();
                    return current == null ? null : current.getCurrentObservation();
                }, CauselinePaths::isIgnoredPath, blockedHeaders);
        FilterRegistrationBean<ReplayCaptureFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(CAPTURE_FILTER_ORDER);
        return registration;
    }

    @Bean
    ReplayService causelineReplayService(ReplayStore records, TraceStore traces, CauselineProperties properties,
            ObjectProvider<ReplayAuthProvider> authProvider, Environment environment) {
        return new ReplayService(records, traces, properties.replay(), Optional.ofNullable(authProvider.getIfAvailable()),
                () -> environment.getProperty("local.server.port", Integer.class, 8080));
    }

    @Bean
    ReplayApiController causelineReplayApiController(ReplayService replays, TraceStore traces) {
        return new ReplayApiController(replays, traces);
    }
}
