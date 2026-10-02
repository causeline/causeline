// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A span sent by {@code @causeline/react}. The start time arrives as a decimal string because
 * epoch nanoseconds exceed JavaScript's safe integer range.
 */
public record BrowserSpan(
        String traceId,
        String spanId,
        String parentSpanId,
        SpanKind kind,
        String name,
        String startTimeUnixNano,
        long durationNanos,
        SpanStatus status,
        Map<String, String> attributes) {

    public static final String SOURCE = "browser";

    /** Kinds a browser can legitimately report; anything else is rejected. */
    private static final Set<SpanKind> BROWSER_KINDS =
            EnumSet.of(SpanKind.UI_ACTION, SpanKind.REQUEST, SpanKind.STATE_UPDATE, SpanKind.RENDER, SpanKind.EXCEPTION);

    /** @throws IllegalArgumentException when the span is malformed or claims a server-side kind */
    public Span toSpan() {
        if (kind == null || !BROWSER_KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind not allowed from the browser: " + kind);
        }
        long start;
        try {
            start = Long.parseLong(startTimeUnixNano);
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("startTimeUnixNano is not an integer: " + startTimeUnixNano);
        }
        return new Span(traceId, spanId, parentSpanId, kind, name, SOURCE, start, durationNanos,
                status == null ? SpanStatus.UNSET : status, attributes);
    }
}
