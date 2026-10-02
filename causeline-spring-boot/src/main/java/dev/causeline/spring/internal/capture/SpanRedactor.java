// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import dev.causeline.core.Span;
import dev.causeline.spring.internal.replay.BodyRedactor;
import dev.causeline.spring.internal.tracing.SpanMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Hides secrets in spans that leave the application (OTLP export, exported trace files). The
 * local UI shows spans as captured; this is the boundary where credentials stop.
 *
 * <p>Redacted: credential headers, sensitive query values and body fields (built-in list plus
 * {@code causeline.capture.redact-keys}), values of state keys that look sensitive, and every
 * literal in SQL text.
 */
public class SpanRedactor {

    /** Used when {@code causeline.export.redact-secrets=false}: spans leave as captured. */
    public static final SpanRedactor NONE = new SpanRedactor(null) {
        @Override
        public Span redact(Span span) {
            return span;
        }
    };

    private static final String HEADER_PREFIX = "http.request.header.";

    private final SensitiveData sensitive;

    public SpanRedactor(SensitiveData sensitive) {
        this.sensitive = sensitive;
    }

    public Span redact(Span span) {
        Map<String, String> out = new LinkedHashMap<>();
        span.attributes().forEach((key, value) -> out.put(key, redactAttribute(key, value, span.attributes())));
        return new Span(span.traceId(), span.spanId(), span.parentSpanId(), span.kind(), span.name(), span.source(),
                span.startTimeUnixNano(), span.durationNanos(), span.status(), out);
    }

    private String redactAttribute(String key, String value, Map<String, String> all) {
        if (key.startsWith(HEADER_PREFIX)) {
            return SensitiveData.isCredentialHeader(key.substring(HEADER_PREFIX.length())) ? SensitiveData.REDACTED : value;
        }
        return switch (key) {
            case "url.query" -> redactQuery(value);
            case "url.full" -> redactUrl(value);
            case "http.request.body", "http.response.body", "causeline.arguments", "causeline.return" -> redactBody(value);
            case "db.query.text", "db.query.statement" -> SpanMapper.sanitizeSql(value);
            case "db.query.parameters" -> SensitiveData.REDACTED;
            case "causeline.state.value" -> sensitive.isSensitiveKey(all.getOrDefault("causeline.state.key", ""))
                    ? SensitiveData.REDACTED
                    : value;
            default -> value;
        };
    }

    private String redactUrl(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q + 1) + redactQuery(url.substring(q + 1));
    }

    String redactQuery(String query) {
        StringJoiner out = new StringJoiner("&");
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            out.add(eq >= 0 && sensitive.isSensitiveKey(key) ? pair.substring(0, eq) + "=" + SensitiveData.REDACTED : pair);
        }
        return out.toString();
    }

    /** JSON and form bodies have their sensitive fields hidden; anything else is withheld whole. */
    private String redactBody(String body) {
        String trimmed = body.stripLeading();
        String type = trimmed.startsWith("{") || trimmed.startsWith("[") ? "application/json"
                : body.contains("=") ? "application/x-www-form-urlencoded" : null;
        try {
            byte[] redacted = type == null ? null
                    : new BodyRedactor(sensitive).redact(type, body.getBytes(StandardCharsets.UTF_8));
            return redacted == null ? "[body withheld on export]" : new String(redacted, StandardCharsets.UTF_8);
        } catch (LinkageError e) {
            return "[body withheld on export]"; // no JSON library in this application
        }
    }
}
