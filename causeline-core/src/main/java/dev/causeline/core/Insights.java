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
    /** Lazy loading shows up with as few rows as a page of results, so SELECTs are flagged sooner. */
    static final int REPEATED_SELECT_MIN = 5;
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

    /**
     * Identical statements under one parent. For SELECTs the label names where they ran and the query
     * that most likely produced the rows they load (the "1" of N+1), and how to load them together.
     */
    private static void repeatedQueries(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.spanId(), r));
        Map<String, List<TraceView.Row>> groups = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> r.kind() == SpanKind.DATABASE)
                .forEach(r -> groups.computeIfAbsent(r.parentSpanId() + "|" + statementOf(r), k -> new ArrayList<>()).add(r));
        groups.values().forEach(group -> {
            TraceView.Row first = group.getFirst();
            boolean select = first.name().startsWith("SELECT");
            if (group.size() < (select ? REPEATED_SELECT_MIN : REPEATED_QUERY_MIN)) {
                return;
            }
            TraceView.Row parent = byId.get(first.parentSpanId());
            String where = parent == null ? "" : " in " + parent.name();
            String label;
            if (select) {
                TraceView.Row before = queryBefore(rows, first);
                String table = first.attributes().getOrDefault("db.collection.name", first.name().substring(6).trim());
                label = "N+1 query: " + first.name() + " ran " + group.size() + " times" + where
                        + (before == null ? "" : ", once per row of " + before.name())
                        + ". Load " + table + " in the same query: JOIN FETCH or @EntityGraph on the repository method,"
                        + " or @BatchSize / hibernate.default_batch_fetch_size";
            } else {
                label = "Repeated query × " + group.size() + ": " + first.name() + where
                        + " (consider a batch statement or saveAll)";
            }
            insights.add(new Insight(Rule.REPEATED_QUERY, first.spanId(), label, 0));
        });
    }

    /** Statements that differ only in their values count as the same query. */
    private static String statementOf(TraceView.Row row) {
        String prepared = row.attributes().get("db.query.statement");
        return prepared != null ? prepared : row.name();
    }

    /** The last query that finished before the repeated ones started, from a different statement. */
    private static TraceView.Row queryBefore(List<TraceView.Row> rows, TraceView.Row first) {
        TraceView.Row best = null;
        for (TraceView.Row r : rows) {
            if (r.kind() == SpanKind.DATABASE && r.offsetNanos() + r.durationNanos() <= first.offsetNanos()
                    && !statementOf(r).equals(statementOf(first)) && r.name().startsWith("SELECT")
                    && (best == null || r.offsetNanos() > best.offsetNanos())) {
                best = r;
            }
        }
        return best;
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
