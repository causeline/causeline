// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

/**
 * One line of the trace list.
 *
 * @param replayOf      the original trace this one replays, or null
 * @param imported      whether the trace was loaded from an exported file rather than recorded here
 * @param baselineNanos the usual duration of this action (median of other successful runs), or null
 *                      when there are too few runs to say; see {@link Baselines}
 * @param baselineRuns  how many runs the baseline is based on
 */
public record TraceSummary(
        String traceId,
        String name,
        SpanStatus status,
        long startTimeUnixNano,
        long durationNanos,
        int spanCount,
        String replayOf,
        boolean imported,
        Long baselineNanos,
        int baselineRuns) {

    public TraceSummary(String traceId, String name, SpanStatus status, long startTimeUnixNano, long durationNanos,
            int spanCount, String replayOf, boolean imported) {
        this(traceId, name, status, startTimeUnixNano, durationNanos, spanCount, replayOf, imported, null, 0);
    }

    public TraceSummary withReplayOf(String originalTraceId) {
        return new TraceSummary(traceId, name, status, startTimeUnixNano, durationNanos, spanCount, originalTraceId,
                imported, baselineNanos, baselineRuns);
    }

    public TraceSummary withImported(boolean isImported) {
        return new TraceSummary(traceId, name, status, startTimeUnixNano, durationNanos, spanCount, replayOf,
                isImported, baselineNanos, baselineRuns);
    }

    public TraceSummary withBaseline(Long usualNanos, int runs) {
        return new TraceSummary(traceId, name, status, startTimeUnixNano, durationNanos, spanCount, replayOf,
                imported, usualNanos, runs);
    }
}
