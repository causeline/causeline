// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.List;
import java.util.Map;

/**
 * A trace ready for display: spans in tree order with times relative to the trace start.
 * Relative nanoseconds stay well inside JavaScript's safe integer range.
 */
public record TraceView(
        String traceId,
        String name,
        SpanStatus status,
        long startTimeUnixNano,
        long durationNanos,
        List<Row> spans,
        List<Insight> insights) {

    /**
     * @param depth      nesting level; 0 for roots
     * @param offsetNanos start relative to the trace start, after clock-skew placement
     * @param selfNanos  duration minus the union of child durations
     * @param clockSkew  true when this span (or its source subtree) could not be placed inside its parent
     */
    public record Row(
            String spanId,
            String parentSpanId,
            SpanKind kind,
            String name,
            String source,
            SpanStatus status,
            int depth,
            long offsetNanos,
            long durationNanos,
            long selfNanos,
            boolean clockSkew,
            Map<String, String> attributes) {
    }

    public TraceSummary summary() {
        return new TraceSummary(traceId, name, status, startTimeUnixNano, durationNanos, spans.size(), null, false);
    }
}
