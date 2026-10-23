// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the traces of many runs of one request into per-span self-time distributions, for a
 * baseline (hits sent one at a time) and for the load (PRD section 8, load replay).
 *
 * <p>Spans are matched across hits the way {@link TraceComparison} matches them: by kind and name,
 * and by position among siblings with the same kind and name, starting from the replayed server
 * request. Only names, kinds and timings are kept, never attribute values, so a report can't carry
 * a captured value.
 */
public final class LoadAggregate {

    public enum Phase {
        BASELINE,
        LOAD
    }

    /**
     * @param baselineHits hits of the baseline in which this span appeared
     * @param loadHits     hits of the load in which it appeared
     * @param grew         self-time p95 grew by at least 20 ms and at least 25% under load
     */
    public record Row(int depth, SpanKind kind, String name, Long baselineP50, Long baselineP95, Long loadP50,
            Long loadP95, int baselineHits, int loadHits, boolean grew) {

        /** Growth of p95 self time; null when the span is missing from one phase. */
        public Long deltaP95() {
            return baselineP95 == null || loadP95 == null ? null : loadP95 - baselineP95;
        }
    }

    /** Exceptions seen in the hits, by class and the line of application code that threw them. */
    public record Failure(String exceptionType, String codeLocation, boolean handled, int baselineHits, int loadHits) {
    }

    /**
     * @param baselineDatabaseSpans median DATABASE spans per baseline hit; null with no hits
     * @param loadDatabaseSpans     median DATABASE spans per load hit; null with no hits
     */
    public record Report(int baselineHits, int loadHits, Double baselineDatabaseSpans, Double loadDatabaseSpans,
            List<Row> rows, List<Failure> failures) {
    }

    private static final class Entry {
        final int depth;
        final SpanKind kind;
        final String name;
        final Map<Phase, List<Long>> selfNanos = new EnumMap<>(Phase.class);

        Entry(int depth, SpanKind kind, String name) {
            this.depth = depth;
            this.kind = kind;
            this.name = name;
            for (Phase phase : Phase.values()) {
                selfNanos.put(phase, new ArrayList<>());
            }
        }
    }

    private static final class FailureCount {
        final Map<Phase, Integer> hits = new EnumMap<>(Map.of(Phase.BASELINE, 0, Phase.LOAD, 0));
    }

    // Insertion order is tree order of the first hit that had each span.
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, FailureCount> failures = new LinkedHashMap<>();
    private final Map<Phase, List<Integer>> databaseSpans = new EnumMap<>(Phase.class);
    private final Map<Phase, Integer> hits = new EnumMap<>(Phase.class);

    public LoadAggregate() {
        for (Phase phase : Phase.values()) {
            databaseSpans.put(phase, new ArrayList<>());
            hits.put(phase, 0);
        }
    }

    /**
     * Adds one hit. Its root is the trace's first REQUEST span: the replayed server request, whose
     * parent (the replay's {@code traceparent}) is not part of the trace.
     */
    public synchronized void add(Phase phase, TraceView hit) {
        Map<String, List<TraceView.Row>> children = new HashMap<>();
        Map<String, TraceView.Row> byId = new HashMap<>();
        hit.spans().forEach(r -> byId.put(r.spanId(), r));
        TraceView.Row root = null;
        for (TraceView.Row r : hit.spans()) {
            if (r.kind() == SpanKind.LOG) {
                continue;
            }
            if (r.parentSpanId() != null && byId.containsKey(r.parentSpanId())) {
                children.computeIfAbsent(r.parentSpanId(), k -> new ArrayList<>()).add(r);
            } else if (root == null && r.kind() == SpanKind.REQUEST) {
                root = r;
            }
        }
        if (root == null) {
            return;
        }
        hits.merge(phase, 1, Integer::sum);
        int[] database = {0};
        Map<String, Boolean> failuresInHit = new LinkedHashMap<>();
        walk(phase, root, "", 0, children, database, failuresInHit);
        databaseSpans.get(phase).add(database[0]);
        failuresInHit.forEach((key, handled) ->
                failures.computeIfAbsent(key, k -> new FailureCount()).hits.merge(phase, 1, Integer::sum));
    }

    private void walk(Phase phase, TraceView.Row row, String key, int depth, Map<String, List<TraceView.Row>> children,
            int[] database, Map<String, Boolean> failuresInHit) {
        Entry entry = entries.computeIfAbsent(key, k -> new Entry(depth, row.kind(), row.name()));
        entry.selfNanos.get(phase).add(row.selfNanos());
        if (row.kind() == SpanKind.DATABASE) {
            database[0]++;
        }
        if (row.kind() == SpanKind.EXCEPTION) {
            boolean handled = "true".equals(row.attributes().get(TraceAssembler.HANDLED_ATTRIBUTE));
            failuresInHit.putIfAbsent(row.attributes().getOrDefault("exception.type", row.name()) + "\u0000"
                    + row.attributes().getOrDefault("code.location", "") + "\u0000" + handled, handled);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (TraceView.Row child : children.getOrDefault(row.spanId(), List.of())) {
            String sibling = child.kind() + "|" + child.name();
            int position = seen.merge(sibling, 1, Integer::sum);
            walk(phase, child, key + "/" + sibling + "#" + position, depth + 1, children, database, failuresInHit);
        }
    }

    public synchronized Report report() {
        List<Row> rows = new ArrayList<>();
        for (Entry entry : entries.values()) {
            List<Long> baseline = sorted(entry.selfNanos.get(Phase.BASELINE));
            List<Long> load = sorted(entry.selfNanos.get(Phase.LOAD));
            Long baselineP95 = percentile(baseline, 0.95);
            Long loadP95 = percentile(load, 0.95);
            rows.add(new Row(entry.depth, entry.kind, entry.name, percentile(baseline, 0.50), baselineP95,
                    percentile(load, 0.50), loadP95, baseline.size(), load.size(), grew(baselineP95, loadP95)));
        }
        // The span that drives the slowdown first; spans missing from a phase after the rest.
        rows.sort(Comparator.comparing(Row::deltaP95, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Failure> failureList = new ArrayList<>();
        failures.forEach((key, count) -> {
            String[] parts = key.split("\u0000", -1);
            failureList.add(new Failure(parts[0], parts[1].isEmpty() ? null : parts[1], Boolean.parseBoolean(parts[2]),
                    count.hits.get(Phase.BASELINE), count.hits.get(Phase.LOAD)));
        });
        return new Report(hits.get(Phase.BASELINE), hits.get(Phase.LOAD), median(databaseSpans.get(Phase.BASELINE)),
                median(databaseSpans.get(Phase.LOAD)), List.copyOf(rows), List.copyOf(failureList));
    }

    static boolean grew(Long baselineP95, Long loadP95) {
        if (baselineP95 == null || loadP95 == null) {
            return false;
        }
        long delta = loadP95 - baselineP95;
        return delta >= TraceComparison.MIN_DELTA_NANOS
                && delta >= TraceComparison.MIN_DELTA_SHARE * Math.max(baselineP95, 1);
    }

    /** Nearest-rank percentile of an ascending list; null when it is empty. */
    public static Long percentile(List<Long> ascending, double p) {
        if (ascending.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p * ascending.size());
        return ascending.get(Math.max(0, Math.min(ascending.size() - 1, rank - 1)));
    }

    private static List<Long> sorted(List<Long> values) {
        List<Long> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    private static Double median(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int n = copy.size();
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2.0;
    }
}
