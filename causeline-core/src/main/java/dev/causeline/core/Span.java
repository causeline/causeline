// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One timed operation in a trace, recorded by the browser or a Spring app.
 * Point events (state updates, exceptions) are spans with a zero duration.
 *
 * @param traceId           32 lowercase hex characters (W3C trace ID)
 * @param spanId            16 lowercase hex characters
 * @param parentSpanId      16 lowercase hex characters, or {@code null} for the root span
 * @param startTimeUnixNano start time on the clock of the source that recorded the span
 * @param durationNanos     inclusive duration; 0 for point events
 */
public record Span(
        String traceId,
        String spanId,
        String parentSpanId,
        SpanKind kind,
        String name,
        String source,
        long startTimeUnixNano,
        long durationNanos,
        SpanStatus status,
        Map<String, String> attributes) {

    /** Version of the span JSON schema. Bump when fields change incompatibly. */
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    public Span {
        requireMatch(TRACE_ID, traceId, "traceId");
        requireMatch(SPAN_ID, spanId, "spanId");
        if (parentSpanId != null) {
            requireMatch(SPAN_ID, parentSpanId, "parentSpanId");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must not be negative: " + durationNanos);
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public long endTimeUnixNano() {
        return startTimeUnixNano + durationNanos;
    }

    // Deliberately not named isRoot(): JSON libraries would serialize an is-getter as a "root" field.
    public boolean hasParent() {
        return parentSpanId != null;
    }

    private static void requireMatch(Pattern pattern, String value, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match " + pattern.pattern() + " but was: " + value);
        }
    }
}
