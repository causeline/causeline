// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.ReplayAuthProvider;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.capture.ReactiveRequestCaptureFilter;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.export.SpanForwarder;
import dev.causeline.spring.internal.export.UpstreamForwarder;
import dev.causeline.spring.internal.replay.ReactiveCaptureWebFilter;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplayStore;
import dev.causeline.spring.internal.tracing.CauselineStats;
import io.micrometer.observation.ObservationPredicate;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * The WebFlux counterpart of {@link CauselineWebConfiguration} and
 * {@link CauselineReplayConfiguration}: the UI at {@code /causeline}, its API, the access guard,
 * request details on server spans, and request capture for replay.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.web.reactive.config.WebFluxConfigurer")
public class CauselineReactiveWebConfiguration {

    private static final ClassPathResource INDEX = new ClassPathResource("causeline-ui/index.html");

    @Bean
    AccessToken causelineAccessToken(CauselineProperties properties) {
        return AccessToken.of(properties.accessToken());
    }

    @Bean
    CauselineSecurityWebFilter causelineSecurityWebFilter(CauselineProperties properties, AccessToken token) {
        return new CauselineSecurityWebFilter(properties, token);
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> causelineStartupLink(AccessToken token) {
        return CauselineWebSupport.startupLink(token);
    }

    @Bean
    CauselineApiController causelineApiController(TraceStore store, ObjectProvider<ReplayStore> replays,
            CauselineStats stats, @Qualifier("causelineForwarder") SpanForwarder causelineForwarder,
            ObjectProvider<UpstreamForwarder> upstream, CauselineProperties properties, Environment environment,
            SpanRedactor redactor) {
        return CauselineWebSupport.apiController(store, replays, stats, causelineForwarder, upstream, properties,
                environment, redactor);
    }

    @Bean
    WebFluxConfigurer causelineUi() {
        return new WebFluxConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(CauselinePaths.BASE_PATH + "/**")
                        .addResourceLocations("classpath:/causeline-ui/");
            }
        };
    }

    /** {@code /causeline} and {@code /causeline/} answer with the UI's index page. */
    @Bean
    RouterFunction<ServerResponse> causelineIndex() {
        return RouterFunctions.route(
                RequestPredicates.GET(CauselinePaths.BASE_PATH).or(RequestPredicates.GET(CauselinePaths.BASE_PATH + "/")),
                request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(INDEX));
    }

    /** Keeps the UI's own requests, and browser housekeeping requests, out of the traces it displays. */
    @Bean
    ObservationPredicate causelineIgnoreOwnRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier() != null
                && CauselinePaths.isIgnoredPath(request.getCarrier().getPath().pathWithinApplication().value()));
    }

    @Bean
    ReactiveRequestCaptureFilter causelineRequestCaptureFilter(CauselineProperties properties) {
        return new ReactiveRequestCaptureFilter(properties.capture(),
                SensitiveData.userBlocked(properties.capture().redactKeys()));
    }

    /** Request capture, replay and comparison (milestone 0.3). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
    static class Replay {

        @Bean
        ReplayStore causelineReplayStore(CauselineProperties properties) {
            return new ReplayStore(properties.store().maxTraces());
        }

        @Bean
        ReactiveCaptureWebFilter causelineReplayCaptureFilter(ReplayStore store, CauselineProperties properties) {
            return new ReactiveCaptureWebFilter(store, properties.capture().requestBody(),
                    properties.capture().responseBody(), CauselineWebSupport.userBodyRedactor(properties),
                    CauselinePaths::isIgnoredPath, CauselineWebSupport.blockedHeaders(properties));
        }

        @Bean
        ReplayService causelineReplayService(ReplayStore records, TraceStore traces, CauselineProperties properties,
                ObjectProvider<ReplayAuthProvider> authProvider, Environment environment) {
            return new ReplayService(records, traces, properties.replay(),
                    Optional.ofNullable(authProvider.getIfAvailable()),
                    () -> environment.getProperty("local.server.port", Integer.class, 8080));
        }

        @Bean
        ReplayApiController causelineReplayApiController(ReplayService replays, TraceStore traces) {
            return new ReplayApiController(replays, traces);
        }
    }
}
