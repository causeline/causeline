// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import dev.causeline.spring.autoconfigure.CauselineProperties.Capture;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Adds request details to server spans: every header except those in
 * {@code causeline.capture.headers.block}, the query string with blocked values hidden, and the
 * raw path. Credentials are included by default; they are shown only in the local UI, and are
 * redacted in anything exported unless {@code causeline.export.redact-secrets=false}.
 *
 * <p>Values are written under {@value #PREFIX} keys, which {@code SpanMapper} renames to
 * OpenTelemetry conventions; nothing else can produce those keys.
 */
public final class RequestCaptureFilter implements ObservationFilter {

    public static final String PREFIX = "causeline.capture.";
    public static final String QUERY = PREFIX + "url.query";
    public static final String PATH = PREFIX + "url.path";
    public static final String HEADER = PREFIX + "header.";
    public static final String BODY = PREFIX + "request.body";
    public static final String RESPONSE_BODY = PREFIX + "response.body";

    static final int MAX_VALUE_CHARS = 4_096;

    private final boolean headers;
    private final Set<String> blockedHeaders;
    private final boolean query;
    private final Set<String> blockedQueryKeys;
    private final boolean pathValues;
    private final SensitiveData userBlocked;

    public RequestCaptureFilter(Capture capture, SensitiveData userBlocked) {
        this.headers = capture.headers().enabled();
        this.blockedHeaders = lower(capture.headers().block());
        this.query = capture.query().enabled();
        this.blockedQueryKeys = Set.copyOf(capture.query().block());
        this.pathValues = capture.pathValues();
        this.userBlocked = userBlocked;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ServerRequestObservationContext server)) {
            return context;
        }
        HttpServletRequest request = server.getCarrier();
        if (headers) {
            for (String name : Collections.list(request.getHeaderNames())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!blockedHeaders.contains(lower)) {
                    // Repeated headers (e.g. two Accept lines) are joined, as HTTP allows.
                    String value = String.join(", ", Collections.list(request.getHeaders(name)));
                    context.addHighCardinalityKeyValue(KeyValue.of(HEADER + lower, truncate(value)));
                }
            }
        }
        String queryString = request.getQueryString();
        if (query && queryString != null && !queryString.isBlank()) {
            context.addHighCardinalityKeyValue(KeyValue.of(QUERY, truncate(blockQueryValues(queryString))));
        }
        if (pathValues) {
            context.addHighCardinalityKeyValue(KeyValue.of(PATH,
                    truncate(request.getRequestURI().substring(request.getContextPath().length()))));
        }
        return context;
    }

    /** Values of blocked keys, and of keys listed in redact-keys, become [REDACTED]. */
    String blockQueryValues(String queryString) {
        StringJoiner out = new StringJoiner("&");
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            boolean hide = blockedQueryKeys.contains(key) || userBlocked.isSensitiveKey(key);
            out.add(eq < 0 ? rawKey : rawKey + "=" + (hide ? SensitiveData.REDACTED : pair.substring(eq + 1)));
        }
        return out.toString();
    }

    static String truncate(String value) {
        return value.length() <= MAX_VALUE_CHARS ? value : value.substring(0, MAX_VALUE_CHARS) + "…";
    }

    private static Set<String> lower(List<String> names) {
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
