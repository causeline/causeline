// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.export.SpanForwarder;
import dev.causeline.spring.internal.export.UpstreamForwarder;
import dev.causeline.spring.internal.replay.ReplayStore;
import dev.causeline.spring.internal.tracing.CauselineStats;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.observation.ObservationPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the Causeline UI at {@code /causeline} and its API at {@code /causeline/api}.
 * The UI lives in a private classpath folder, so it is reachable only through this configuration,
 * which only exists when Causeline is enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CauselineWebConfiguration {

    static final String BASE_PATH = "/causeline";

    private static final Logger log = LoggerFactory.getLogger(CauselineWebConfiguration.class);

    @Bean
    AccessToken causelineAccessToken(CauselineProperties properties) {
        return AccessToken.of(properties.accessToken());
    }

    @Bean
    FilterRegistrationBean<CauselineSecurityFilter> causelineSecurityFilter(CauselineProperties properties,
            AccessToken token) {
        FilterRegistrationBean<CauselineSecurityFilter> registration =
                new FilterRegistrationBean<>(new CauselineSecurityFilter(properties, token));
        registration.addUrlPatterns(BASE_PATH, BASE_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** Prints where to open the UI once the port is known, the way Jupyter does. */
    @Bean
    ApplicationListener<WebServerInitializedEvent> causelineStartupLink(AccessToken token) {
        return event -> {
            String base = "http://localhost:" + event.getWebServer().getPort() + BASE_PATH + "/";
            if (token.generated()) {
                log.info("Causeline UI: {}?token={}  (token changes on every restart)", base, token.value());
            } else {
                log.info("Causeline UI: {}  (use your configured causeline.access-token)", base);
            }
        };
    }

    @Bean
    CauselineApiController causelineApiController(TraceStore store, ObjectProvider<ReplayStore> replays,
            CauselineStats stats, @Qualifier("causelineForwarder") SpanForwarder causelineForwarder,
            ObjectProvider<UpstreamForwarder> upstream,
            CauselineProperties properties, Environment environment, SpanRedactor redactor) {
        CauselineApiController.Status.Otlp otlpStatus = properties.export().otlp().enabled()
                ? new CauselineApiController.Status.Otlp(true, URI.create(properties.export().otlp().endpoint()).getHost(), 0, 0, 0)
                : CauselineApiController.Status.Otlp.disabled();
        CauselineApiController controller = new CauselineApiController(store, traceId -> {
            ReplayStore replayStore = replays.getIfAvailable();
            return replayStore == null ? Optional.empty() : replayStore.replayOf(traceId);
        }, stats, causelineForwarder, environment.getProperty("spring.application.name", "spring"), otlpStatus, redactor);
        UpstreamForwarder up = upstream.getIfAvailable();
        if (up != null) {
            controller.setUpstream(up.host());
        }
        return controller;
    }

    @Bean
    WebMvcConfigurer causelineUi() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(BASE_PATH + "/**").addResourceLocations("classpath:/causeline-ui/");
            }

            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController(BASE_PATH).setViewName("forward:" + BASE_PATH + "/index.html");
                registry.addViewController(BASE_PATH + "/").setViewName("forward:" + BASE_PATH + "/index.html");
            }
        };
    }

    /**
     * Keeps the UI's own polling and span uploads out of the traces it displays, along with browser
     * housekeeping requests (favicon, Chrome's .well-known probes) that are never user actions.
     */
    @Bean
    ObservationPredicate causelineIgnoreOwnRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && isIgnoredPath(request.getCarrier().getRequestURI()));
    }

    static boolean isCauselinePath(String uri) {
        return uri != null && (uri.equals(BASE_PATH) || uri.startsWith(BASE_PATH + "/"));
    }

    static boolean isIgnoredPath(String uri) {
        return isCauselinePath(uri)
                || "/favicon.ico".equals(uri)
                || (uri != null && uri.startsWith("/.well-known/"));
    }
}
