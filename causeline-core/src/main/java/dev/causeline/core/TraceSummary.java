// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

/**
 * One line of the trace list.
 *
 * @param replayOf the original trace this one replays, or null
 * @param imported whether the trace was loaded from an exported file rather than recorded here
 */
public record TraceSummary(
        String traceId,
        String name,
        SpanStatus status,
        long startTimeUnixNano,
        long durationNanos,
        int spanCount,
        String replayOf,
        boolean imported) {

    public TraceSummary withReplayOf(String originalTraceId) {
        return new TraceSummary(traceId, name, status, startTimeUnixNano, durationNanos, spanCount, originalTraceId,
                imported);
    }

    public TraceSummary withImported(boolean isImported) {
        return new TraceSummary(traceId, name, status, startTimeUnixNano, durationNanos, spanCount, replayOf,
                isImported);
    }
}
