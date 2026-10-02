// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import dev.causeline.spring.autoconfigure.CauselineProperties.Capture;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Request details for server spans, whatever the web stack: every header except those in
 * {@code causeline.capture.headers.block}, the query string with blocked values hidden, and the raw
 * path. Credentials are included by default; they are shown only in the local UI, and are redacted
 * in anything exported unless {@code causeline.export.redact-secrets=false}.
 *
 * <p>Values are written under {@value RequestCaptureFilter#PREFIX} keys, which {@code SpanMapper}
 * renames to OpenTelemetry conventions; nothing else can produce those keys.
 */
public final class RequestDetails {

    static final int MAX_VALUE_CHARS = 4_096;

    private final boolean headers;
    private final Set<String> blockedHeaders;
    private final boolean query;
    private final Set<String> blockedQueryKeys;
    private final boolean pathValues;
    private final SensitiveData userBlocked;

    public RequestDetails(Capture capture, SensitiveData userBlocked) {
        this.headers = capture.headers().enabled();
        this.blockedHeaders = lower(capture.headers().block());
        this.query = capture.query().enabled();
        this.blockedQueryKeys = Set.copyOf(capture.query().block());
        this.pathValues = capture.pathValues();
        this.userBlocked = userBlocked;
    }

    /**
     * @param headerValues each header name with all its values
     * @param queryString  the raw query string, or null
     * @param path         the path within the application
     */
    public void addTo(Observation.Context context, Map<String, List<String>> headerValues, String queryString,
            String path) {
        if (headers) {
            headerValues.forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!blockedHeaders.contains(lower)) {
                    // Repeated headers (e.g. two Accept lines) are joined, as HTTP allows.
                    context.addHighCardinalityKeyValue(
                            KeyValue.of(RequestCaptureFilter.HEADER + lower, truncate(String.join(", ", values))));
                }
            });
        }
        if (query && queryString != null && !queryString.isBlank()) {
            context.addHighCardinalityKeyValue(
                    KeyValue.of(RequestCaptureFilter.QUERY, truncate(blockQueryValues(queryString))));
        }
        if (pathValues && path != null) {
            context.addHighCardinalityKeyValue(KeyValue.of(RequestCaptureFilter.PATH, truncate(path)));
        }
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
