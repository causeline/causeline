// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Slower than usual": for each trace, the median duration of the <em>other</em> runs of the same
 * action. Only successful, recorded runs count (failures and replays would skew it), and a
 * baseline needs at least {@link #MIN_RUNS} of them, so one earlier run can't make everything look
 * slow. The median ignores the occasional cold start that an average would not.
 */
public final class Baselines {

    public static final int MIN_RUNS = 3;

    private Baselines() {
    }

    public static List<TraceSummary> apply(List<TraceSummary> summaries) {
        Map<String, List<TraceSummary>> byName = new HashMap<>();
        for (TraceSummary s : summaries) {
            if (counts(s)) {
                byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
            }
        }
        List<TraceSummary> out = new ArrayList<>(summaries.size());
        for (TraceSummary s : summaries) {
            List<Long> others = new ArrayList<>();
            for (TraceSummary run : byName.getOrDefault(s.name(), List.of())) {
                if (!run.traceId().equals(s.traceId())) {
                    others.add(run.durationNanos());
                }
            }
            out.add(others.size() >= MIN_RUNS ? s.withBaseline(median(others), others.size()) : s);
        }
        return out;
    }

    private static boolean counts(TraceSummary s) {
        return s.status() != SpanStatus.ERROR && s.replayOf() == null && !s.imported();
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
