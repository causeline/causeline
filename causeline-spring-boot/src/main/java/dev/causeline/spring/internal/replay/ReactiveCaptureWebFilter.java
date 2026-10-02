// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import dev.causeline.spring.autoconfigure.CauselineProperties.RequestBodyCapture;
import dev.causeline.spring.autoconfigure.CauselineProperties.ResponseBodyCapture;
import dev.causeline.spring.internal.capture.RequestCaptureFilter;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The WebFlux counterpart of {@link ReplayCaptureFilter}: keeps each traced request so it can be
 * shown and replayed, and with {@code full} capture attaches the request and response bodies to
 * the server span. Bodies are copied as they stream past, up to {@value CapturedBodies#MAX_BODY_BYTES}
 * bytes; nothing is held back or read ahead of the application.
 */
public final class ReactiveCaptureWebFilter implements WebFilter, Ordered {

    private final ReplayStore store;
    private final RequestBodyCapture mode;
    private final ResponseBodyCapture responseMode;
    private final BodyRedactor userRedactor;
    private final Predicate<String> ignoredPath;
    private final Set<String> blockedHeaders;

    /** @param userRedactor hides only the user's {@code redact-keys}; null when there are none */
    public ReactiveCaptureWebFilter(ReplayStore store, RequestBodyCapture mode, ResponseBodyCapture responseMode,
            BodyRedactor userRedactor, Predicate<String> ignoredPath, Set<String> blockedHeaders) {
        this.store = store;
        this.mode = mode;
        this.responseMode = responseMode;
        this.userRedactor = userRedactor;
        this.ignoredPath = ignoredPath;
        this.blockedHeaders = blockedHeaders;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (ignoredPath.test(request.getPath().pathWithinApplication().value())) {
            return chain.filter(exchange);
        }
        Observation.Context context = ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .orElse(null);
        String[] span = context == null ? null : span(context);
        if (span == null) {
            return chain.filter(exchange);
        }
        Tap requestBody = mode != RequestBodyCapture.NONE ? new Tap() : null;
        Tap responseBody = responseMode == ResponseBodyCapture.FULL ? new Tap() : null;
        ServerWebExchange.Builder decorated = exchange.mutate();
        if (requestBody != null) {
            decorated.request(new ServerHttpRequestDecorator(request) {
                @Override
                public Flux<DataBuffer> getBody() {
                    return super.getBody().doOnNext(requestBody::keep);
                }
            });
        }
        if (responseBody != null) {
            decorated.response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                @Override
                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                    return super.writeWith(Flux.from(body).doOnNext(responseBody::keep));
                }

                @Override
                public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                    return super.writeAndFlushWith(Flux.from(body).map(part -> Flux.from(part).doOnNext(responseBody::keep)));
                }
            });
        }
        // doOnTerminate runs before the completion reaches Spring, which then closes the span.
        return chain.filter(decorated.build())
                .doOnTerminate(() -> record(request, exchange.getResponse(), context, span, requestBody, responseBody));
    }

    private void record(ServerHttpRequest request, ServerHttpResponse response, Observation.Context context,
            String[] span, Tap requestBody, Tap responseBody) {
        try {
            String replayOf = request.getHeaders().getFirst(ReplayService.REPLAY_HEADER);
            if (replayOf != null && CapturedBodies.TRACE_ID.matcher(replayOf).matches()) {
                store.markReplay(span[0], replayOf);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            request.getHeaders().forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!CapturedBodies.NOT_REPLAYED.contains(lower) && !blockedHeaders.contains(lower)) {
                    headers.put(name, String.join(", ", values));
                }
            });
            MediaType requestType = request.getHeaders().getContentType();
            String contentType = requestType == null ? null : requestType.toString();
            byte[] body = null;
            if (requestBody != null && requestBody.total > 0) {
                body = CapturedBodies.hideBlockedKeys(userRedactor, contentType, requestBody.copy.toByteArray());
                if (mode == RequestBodyCapture.FULL) {
                    context.addHighCardinalityKeyValue(
                            KeyValue.of(RequestCaptureFilter.BODY, CapturedBodies.shownRequest(contentType, body)));
                }
            }
            if (responseBody != null && responseBody.total > 0) {
                MediaType type = response.getHeaders().getContentType();
                Charset charset = type == null || type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
                context.addHighCardinalityKeyValue(KeyValue.of(RequestCaptureFilter.RESPONSE_BODY,
                        CapturedBodies.shownResponse(userRedactor, type == null ? null : type.toString(), charset,
                                responseBody.copy.toByteArray(), responseBody.total)));
            }
            String query = request.getURI().getRawQuery();
            long length = request.getHeaders().getContentLength();
            store.put(new ReplayRecord(
                    span[0],
                    span[1],
                    request.getMethod().name(),
                    request.getPath().pathWithinApplication().value(),
                    mode == RequestBodyCapture.NONE ? null : query,
                    Map.copyOf(headers),
                    body,
                    query != null,
                    length > 0 || body != null,
                    request.getHeaders().getFirst("Idempotency-Key") != null));
        } catch (RuntimeException e) {
            // Capturing must never affect the application's response.
        }
    }

    /** The server span of this request as {traceId, spanId}, or null when it is not traced. */
    private static String[] span(Observation.Context context) {
        TracingObservationHandler.TracingContext tracing = context.get(TracingObservationHandler.TracingContext.class);
        if (tracing == null || tracing.getSpan() == null) {
            return null;
        }
        return new String[] {tracing.getSpan().context().traceId(), tracing.getSpan().context().spanId()};
    }

    /** The first bytes that streamed past, and how many there were in all. */
    private static final class Tap {

        private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
        private long total;

        synchronized void keep(DataBuffer buffer) {
            total += buffer.readableByteCount();
            int room = CapturedBodies.MAX_BODY_BYTES - copy.size();
            if (room <= 0) {
                return;
            }
            // Read-only views: the application still reads the buffer from where it was.
            try (DataBuffer.ByteBufferIterator views = buffer.readableByteBuffers()) {
                while (views.hasNext() && room > 0) {
                    ByteBuffer view = views.next();
                    byte[] bytes = new byte[Math.min(room, view.remaining())];
                    view.get(bytes);
                    copy.write(bytes, 0, bytes.length);
                    room -= bytes.length;
                }
            }
        }
    }
}
