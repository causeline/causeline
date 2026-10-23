// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import dev.causeline.spring.autoconfigure.CauselineProperties.Capture;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds {@link RequestDetails} to the server spans of a WebFlux application.
 *
 * <p>Also a {@link WebFilter}: by the time the observation stops, Reactor Netty may already have
 * sent and released the response headers, so they are copied just before the response commits.
 */
public final class ReactiveRequestCaptureFilter implements ObservationFilter, WebFilter, Ordered {

    static final String RESPONSE_HEADERS = ReactiveRequestCaptureFilter.class.getName() + ".responseHeaders";

    private final RequestDetails details;

    public ReactiveRequestCaptureFilter(Capture capture, SensitiveData userBlocked) {
        this.details = new RequestDetails(capture, userBlocked);
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            response.getHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            exchange.getAttributes().put(RESPONSE_HEADERS, headers);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ServerRequestObservationContext server) || server.getCarrier() == null) {
            return context;
        }
        ServerHttpRequest request = server.getCarrier();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.getHeaders().forEach(headers::put);
        details.addTo(context, headers, request.getURI().getRawQuery(),
                request.getPath().pathWithinApplication().value());
        Object responseHeaders = server.getAttributes().get(RESPONSE_HEADERS);
        if (responseHeaders instanceof Map<?, ?> copied) {
            details.addResponseHeaders(context, (Map<String, List<String>>) copied);
        }
        return context;
    }
}
