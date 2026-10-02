// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import dev.causeline.core.Insight.Rule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rule-based analysis of an assembled trace (PRD section 7). No AI, no heuristics beyond these rules. */
public final class Insights {

    static final long NOTABLE_MIN_NANOS = 100_000_000;
    static final int REPEATED_QUERY_MIN = 10;
    static final long SLOW_CLIENT_MIN_NANOS = 100_000_000;

    private Insights() {
    }

    public static List<Insight> analyze(List<TraceView.Row> rows, long traceDurationNanos) {
        List<Insight> insights = new ArrayList<>();
        if (rows.isEmpty() || traceDurationNanos <= 0) {
            return insights;
        }
        selfTimeRules(rows, traceDurationNanos, insights);
        repeatedQueries(rows, insights);
        slowClientHandling(rows, insights);
        handledExceptions(rows, insights);
        return insights;
    }

    private static void handledExceptions(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.spanId(), r));
        rows.stream()
                .filter(r -> r.kind() == SpanKind.EXCEPTION
                        && "true".equals(r.attributes().get(TraceAssembler.HANDLED_ATTRIBUTE)))
                .forEach(r -> {
                    TraceView.Row parent = byId.get(r.parentSpanId());
                    String where = parent == null ? "" : " in " + parent.name()
                            + " (" + ms(parent.durationNanos()) + ")";
                    String level = r.attributes().getOrDefault("log.level", "WARN");
                    insights.add(new Insight(Rule.HANDLED_EXCEPTION, r.spanId(),
                            "Handled exception: " + r.name() + where + ", caught and logged at " + level
                                    + "; the request continued",
                            0));
                });
    }

    /**
     * Root spans are left out: their self time is whatever the instrumentation below them did not
     * cover, and a one-span trace would otherwise always be "100% bottleneck".
     */
    private static void selfTimeRules(List<TraceView.Row> rows, long total, List<Insight> insights) {
        List<TraceView.Row> candidates = rows.stream().filter(r -> r.depth() > 0).toList();
        if (candidates.isEmpty()) {
            return;
        }
        TraceView.Row top = candidates.stream().max(Comparator.comparingLong(TraceView.Row::selfNanos)).orElseThrow();
        boolean primary = top.selfNanos() * 2 >= total;
        if (primary) {
            insights.add(new Insight(Rule.PRIMARY_BOTTLENECK, top.spanId(),
                    "Potential bottleneck: " + top.name() + " · " + ms(top.selfNanos()) + " · " + share(top, total) + "%",
                    share(top, total)));
        }
        candidates.stream()
                .filter(r -> !(primary && r == top))
                .filter(r -> r.selfNanos() * 4 >= total && r.selfNanos() >= NOTABLE_MIN_NANOS)
                .sorted(Comparator.comparingLong(TraceView.Row::selfNanos).reversed())
                .forEach(r -> insights.add(new Insight(Rule.NOTABLE, r.spanId(),
                        "Notable: " + r.name() + " · " + ms(r.selfNanos()) + " · " + share(r, total) + "%",
                        share(r, total))));
    }

    private static void repeatedQueries(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, List<TraceView.Row>> groups = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> r.kind() == SpanKind.DATABASE)
                .forEach(r -> groups.computeIfAbsent(r.parentSpanId() + "|" + r.name(), k -> new ArrayList<>()).add(r));
        groups.values().stream()
                .filter(group -> group.size() >= REPEATED_QUERY_MIN)
                .forEach(group -> insights.add(new Insight(Rule.REPEATED_QUERY, group.getFirst().spanId(),
                        "Repeated query × " + group.size() + ": " + group.getFirst().name()
                                + " (possible N+1; consider a join or batch fetch)",
                        0)));
    }

    private static void slowClientHandling(List<TraceView.Row> rows, List<Insight> insights) {
        List<TraceView.Row> stateUpdates = rows.stream().filter(r -> r.kind() == SpanKind.STATE_UPDATE).toList();
        if (stateUpdates.isEmpty()) {
            return;
        }
        rows.stream()
                .filter(r -> r.kind() == SpanKind.REQUEST && "browser".equals(r.source()))
                .forEach(request -> {
                    long end = request.offsetNanos() + request.durationNanos();
                    stateUpdates.stream()
                            .filter(s -> s.offsetNanos() >= end)
                            .mapToLong(TraceView.Row::offsetNanos)
                            .min()
                            .ifPresent(next -> {
                                long gap = next - end;
                                if (gap >= SLOW_CLIENT_MIN_NANOS) {
                                    insights.add(new Insight(Rule.SLOW_CLIENT_HANDLING, request.spanId(),
                                            "Slow client handling: " + ms(gap)
                                                    + " between the response to " + request.name()
                                                    + " and the next state update",
                                            0));
                                }
                            });
                });
    }

    private static int share(TraceView.Row row, long total) {
        return (int) Math.round(row.selfNanos() * 100.0 / total);
    }

    private static String ms(long nanos) {
        long ms = Math.round(nanos / 1_000_000.0);
        return ms + " ms";
    }
}
