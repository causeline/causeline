// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.export.SpanForwarder;
import dev.causeline.spring.internal.export.UpstreamForwarder;
import dev.causeline.spring.internal.replay.BodyRedactor;
import dev.causeline.spring.internal.replay.ReplayStore;
import dev.causeline.spring.internal.tracing.CauselineStats;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Beans that the servlet and the reactive web configurations build the same way. Kept free of
 * servlet and WebFlux types, so each stack can use it without loading the other.
 */
final class CauselineWebSupport {

    private static final Logger log = LoggerFactory.getLogger(CauselineWebConfiguration.class);

    private CauselineWebSupport() {
    }

    /** Prints where to open the UI once the port is known, the way Jupyter does. */
    static ApplicationListener<WebServerInitializedEvent> startupLink(AccessToken token) {
        return event -> {
            String base = "http://localhost:" + event.getWebServer().getPort() + CauselinePaths.BASE_PATH + "/";
            if (token.generated()) {
                log.info("Causeline UI: {}?token={}  (token changes on every restart)", base, token.value());
            } else {
                log.info("Causeline UI: {}  (use your configured causeline.access-token)", base);
            }
        };
    }

    static CauselineApiController apiController(TraceStore store, ObjectProvider<ReplayStore> replays,
            CauselineStats stats, SpanForwarder forwarder, ObjectProvider<UpstreamForwarder> upstream,
            CauselineProperties properties, Environment environment, SpanRedactor redactor) {
        CauselineApiController.Status.Otlp otlpStatus = properties.export().otlp().enabled()
                ? new CauselineApiController.Status.Otlp(true,
                        URI.create(properties.export().otlp().endpoint()).getHost(), 0, 0, 0)
                : CauselineApiController.Status.Otlp.disabled();
        CauselineApiController controller = new CauselineApiController(store, traceId -> {
            ReplayStore replayStore = replays.getIfAvailable();
            return replayStore == null ? Optional.empty() : replayStore.replayOf(traceId);
        }, stats, forwarder, environment.getProperty("spring.application.name", "spring"), otlpStatus, redactor);
        UpstreamForwarder up = upstream.getIfAvailable();
        if (up != null) {
            controller.setUpstream(up.host());
        }
        return controller;
    }

    /** Hides only the user's redact-keys in captured bodies; null when there are none. */
    static BodyRedactor userBodyRedactor(CauselineProperties properties) {
        List<String> redactKeys = properties.capture().redactKeys();
        return redactKeys.isEmpty() ? null : new BodyRedactor(SensitiveData.userBlocked(redactKeys));
    }

    static Set<String> blockedHeaders(CauselineProperties properties) {
        return properties.capture().headers().block().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
