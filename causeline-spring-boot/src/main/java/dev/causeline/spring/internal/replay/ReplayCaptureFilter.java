// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import dev.causeline.spring.autoconfigure.CauselineProperties.RequestBodyCapture;
import dev.causeline.spring.autoconfigure.CauselineProperties.ResponseBodyCapture;
import dev.causeline.spring.internal.capture.RequestCaptureFilter;
import dev.causeline.spring.internal.capture.ResponseBodyTee;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Keeps each traced request so it can be shown and replayed. Runs inside Spring's HTTP
 * observation, so the current span is the request's server span.
 *
 * <p>Kept: method, path, query string, every header except blocked ones (credentials included),
 * and the body up to {@value #MAX_BODY_BYTES} bytes, with only {@code causeline.capture.redact-keys}
 * hidden. With {@code request-body: full} the body is also attached to the server span for the UI.
 */
public final class ReplayCaptureFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = CapturedBodies.MAX_BODY_BYTES;

    static final Set<String> NOT_REPLAYED = CapturedBodies.NOT_REPLAYED;

    /** The span active on this thread, as {traceId, spanId}, or null. */
    public interface ActiveSpan extends Supplier<String[]> {
    }

    private final ReplayStore store;
    private final RequestBodyCapture mode;
    private final ResponseBodyCapture responseMode;
    private final BodyRedactor userRedactor;
    private final ActiveSpan activeSpan;
    private final Supplier<Observation> currentObservation;
    private final Predicate<String> ignoredPath;
    private final Set<String> blockedHeaders;

    /**
     * @param userRedactor        hides only the user's {@code redact-keys}; null when there are none
     * @param currentObservation  the request's observation, to attach the body for the UI
     */
    public ReplayCaptureFilter(ReplayStore store, RequestBodyCapture mode, BodyRedactor userRedactor,
            ActiveSpan activeSpan, Supplier<Observation> currentObservation, Predicate<String> ignoredPath,
            Set<String> blockedHeaders) {
        this(store, mode, ResponseBodyCapture.NONE, userRedactor, activeSpan, currentObservation, ignoredPath,
                blockedHeaders);
    }

    /** @param responseMode whether to record response bodies on the server span */
    public ReplayCaptureFilter(ReplayStore store, RequestBodyCapture mode, ResponseBodyCapture responseMode,
            BodyRedactor userRedactor, ActiveSpan activeSpan, Supplier<Observation> currentObservation,
            Predicate<String> ignoredPath, Set<String> blockedHeaders) {
        this.store = store;
        this.mode = mode;
        this.responseMode = responseMode;
        this.userRedactor = userRedactor;
        this.activeSpan = activeSpan;
        this.currentObservation = currentObservation;
        this.ignoredPath = ignoredPath;
        this.blockedHeaders = blockedHeaders;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ignoredPath.test(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean keepBody = mode != RequestBodyCapture.NONE;
        long length = request.getContentLengthLong();
        HttpServletRequest actual = keepBody && length != 0
                ? new ContentCachingRequestWrapper(request, MAX_BODY_BYTES)
                : request;
        ResponseBodyTee tee = responseMode == ResponseBodyCapture.FULL ? new ResponseBodyTee(response, MAX_BODY_BYTES) : null;
        // Read the span before the chain: afterwards the observation may already be closing.
        String[] span = activeSpan.get();
        try {
            chain.doFilter(actual, tee == null ? response : tee);
        } finally {
            if (tee != null) {
                tee.finish();
            }
            if (span != null) {
                record(actual, span, length);
                // An async response is written after this point; there is nothing to show yet.
                if (tee != null && !request.isAsyncStarted()) {
                    attachResponse(tee);
                }
            }
        }
    }

    private void attachResponse(ResponseBodyTee tee) {
        if (tee.totalBytes() == 0) {
            return;
        }
        Observation observation = currentObservation.get();
        if (observation == null) {
            return;
        }
        String shown = CapturedBodies.shownResponse(userRedactor, tee.getContentType(), tee.charset(), tee.copy(),
                tee.totalBytes());
        observation.highCardinalityKeyValue(KeyValue.of(RequestCaptureFilter.RESPONSE_BODY, shown));
    }

    private void record(HttpServletRequest request, String[] span, long length) {
        try {
            String replayOf = request.getHeader(ReplayService.REPLAY_HEADER);
            if (replayOf != null && CapturedBodies.TRACE_ID.matcher(replayOf).matches()) {
                store.markReplay(span[0], replayOf);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : Collections.list(request.getHeaderNames())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!NOT_REPLAYED.contains(lower) && !blockedHeaders.contains(lower)) {
                    headers.put(name, String.join(", ", Collections.list(request.getHeaders(name))));
                }
            }
            byte[] body = null;
            if (request instanceof ContentCachingRequestWrapper cached && cached.getContentAsByteArray().length > 0) {
                body = hideBlockedKeys(request.getContentType(), cached.getContentAsByteArray());
                if (mode == RequestBodyCapture.FULL) {
                    attachToSpan(request.getContentType(), body);
                }
            }
            String query = request.getQueryString();
            store.put(new ReplayRecord(
                    span[0],
                    span[1],
                    request.getMethod(),
                    request.getRequestURI().substring(request.getContextPath().length()),
                    mode == RequestBodyCapture.NONE ? null : query,
                    Map.copyOf(headers),
                    body,
                    query != null,
                    length > 0 || body != null,
                    request.getHeader("Idempotency-Key") != null));
        } catch (RuntimeException e) {
            // Capturing must never affect the application's response.
        }
    }

    private byte[] hideBlockedKeys(String contentType, byte[] body) {
        return CapturedBodies.hideBlockedKeys(userRedactor, contentType, body);
    }

    private void attachToSpan(String contentType, byte[] body) {
        Observation observation = currentObservation.get();
        if (observation == null) {
            return;
        }
        observation.highCardinalityKeyValue(
                KeyValue.of(RequestCaptureFilter.BODY, CapturedBodies.shownRequest(contentType, body)));
    }
}
