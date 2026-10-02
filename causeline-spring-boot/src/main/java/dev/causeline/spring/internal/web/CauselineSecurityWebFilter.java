// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Applies {@link CauselineGuard} to everything under {@code /causeline} in a WebFlux application. */
public final class CauselineSecurityWebFilter implements WebFilter, Ordered {

    private final CauselineGuard guard;

    public CauselineSecurityWebFilter(CauselineProperties properties, AccessToken token) {
        this(properties, token, System::currentTimeMillis);
    }

    CauselineSecurityWebFilter(CauselineProperties properties, AccessToken token, LongSupplier clockMillis) {
        this.guard = new CauselineGuard(properties, token, clockMillis);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (!CauselinePaths.isCauselinePath(path)) {
            return chain.filter(exchange);
        }
        CauselineGuard.Rejection rejection = guard.check(new CauselineGuard.Request(
                request.getURI().getHost(), port(request), path, request.getHeaders().getOrigin(),
                request.getHeaders().getContentLength(), remoteAddress(request),
                request.getHeaders().getFirst(CauselineGuard.TOKEN_HEADER)));
        if (rejection == null) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(rejection.status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = rejection.json().getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static int port(ServerHttpRequest request) {
        int port = request.getURI().getPort();
        return port != -1 ? port : "https".equals(request.getURI().getScheme()) ? 443 : 80;
    }

    private static String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
    }
}
