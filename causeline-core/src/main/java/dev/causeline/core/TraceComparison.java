// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares the original run of a request with its replay, span by span (PRD section 8).
 *
 * <p>Spans are matched by kind and name, and by position among siblings with the same kind and
 * name, starting from the replayed server request in the original trace and the root request of
 * the replay. Unmatched spans on either side are listed, never hidden.
 */
public final class TraceComparison {

    /** A difference is highlighted only when it is at least this large... */
    static final long MIN_DELTA_NANOS = 20_000_000;
    /** ...and at least this share of the original. */
    static final double MIN_DELTA_SHARE = 0.25;

    public enum Change {
        SAME,
        FASTER,
        SLOWER,
        STATUS_CHANGED,
        ONLY_IN_ORIGINAL,
        ONLY_IN_REPLAY
    }

    /**
     * @param originalNanos null when the span exists only in the replay
     * @param replayNanos   null when the span exists only in the original
     */
    public record Row(int depth, SpanKind kind, String name, Long originalNanos, Long replayNanos,
            SpanStatus originalStatus, SpanStatus replayStatus, Change change) {
    }

    public record Side(String traceId, SpanStatus status, long durationNanos, String httpStatus) {
    }

    public record Result(Side original, Side replay, List<Row> rows) {
    }

    private TraceComparison() {
    }

    /**
     * @param original        the original trace
     * @param originalSpanId  the server request span that was replayed
     * @param replay          the replay's trace, as recorded by the target
     */
    public static Result compare(TraceView original, String originalSpanId, TraceView replay) {
        Tree left = Tree.of(original);
        Tree right = Tree.of(replay);
        TraceView.Row leftRoot = left.byId.get(originalSpanId);
        if (leftRoot == null) {
            throw new IllegalArgumentException("span " + originalSpanId + " is not in trace " + original.traceId());
        }
        TraceView.Row rightRoot = right.roots.stream()
                .filter(r -> r.kind() == SpanKind.REQUEST)
                .findFirst()
                .orElse(right.roots.isEmpty() ? null : right.roots.getFirst());

        List<Row> rows = new ArrayList<>();
        match(leftRoot, rightRoot, 0, left, right, rows);
        return new Result(
                side(original.traceId(), leftRoot),
                rightRoot == null ? new Side(replay.traceId(), replay.status(), replay.durationNanos(), null)
                        : side(replay.traceId(), rightRoot, subtreeStatus(rightRoot, right)),
                rows);
    }

    private static Side side(String traceId, TraceView.Row root) {
        return new Side(traceId, root.status(), root.durationNanos(), root.attributes().get("http.response.status_code"));
    }

    private static Side side(String traceId, TraceView.Row root, SpanStatus status) {
        return new Side(traceId, status, root.durationNanos(), root.attributes().get("http.response.status_code"));
    }

    private static SpanStatus subtreeStatus(TraceView.Row root, Tree tree) {
        if (root.status() == SpanStatus.ERROR) {
            return SpanStatus.ERROR;
        }
        for (TraceView.Row child : tree.children(root)) {
            if (subtreeStatus(child, tree) == SpanStatus.ERROR) {
                return SpanStatus.ERROR;
            }
        }
        return root.status();
    }

    private static void match(TraceView.Row left, TraceView.Row right, int depth, Tree leftTree, Tree rightTree,
            List<Row> rows) {
        if (left == null && right == null) {
            return;
        }
        rows.add(row(left, right, depth));
        List<TraceView.Row> leftKids = left == null ? List.of() : leftTree.children(left);
        List<TraceView.Row> rightKids = right == null ? List.of() : rightTree.children(right);

        // Pair children with the same kind and name, in order of appearance.
        Map<String, List<TraceView.Row>> unmatchedRight = new HashMap<>();
        for (TraceView.Row r : rightKids) {
            unmatchedRight.computeIfAbsent(key(r), k -> new ArrayList<>()).add(r);
        }
        for (TraceView.Row l : leftKids) {
            List<TraceView.Row> candidates = unmatchedRight.get(key(l));
            TraceView.Row partner = candidates == null || candidates.isEmpty() ? null : candidates.removeFirst();
            match(l, partner, depth + 1, leftTree, rightTree, rows);
        }
        for (TraceView.Row r : rightKids) {
            if (unmatchedRight.getOrDefault(key(r), List.of()).contains(r)) {
                match(null, r, depth + 1, leftTree, rightTree, rows);
            }
        }
    }

    private static Row row(TraceView.Row left, TraceView.Row right, int depth) {
        TraceView.Row any = left != null ? left : right;
        Long before = left == null ? null : left.durationNanos();
        Long after = right == null ? null : right.durationNanos();
        return new Row(depth, any.kind(), any.name(), before, after,
                left == null ? null : left.status(), right == null ? null : right.status(),
                change(left, right));
    }

    static Change change(TraceView.Row left, TraceView.Row right) {
        if (right == null) {
            return Change.ONLY_IN_ORIGINAL;
        }
        if (left == null) {
            return Change.ONLY_IN_REPLAY;
        }
        if (isError(left.status()) != isError(right.status())) {
            return Change.STATUS_CHANGED;
        }
        long delta = right.durationNanos() - left.durationNanos();
        boolean large = Math.abs(delta) >= MIN_DELTA_NANOS
                && Math.abs(delta) >= MIN_DELTA_SHARE * Math.max(left.durationNanos(), 1);
        if (!large) {
            return Change.SAME;
        }
        return delta < 0 ? Change.FASTER : Change.SLOWER;
    }

    private static boolean isError(SpanStatus status) {
        return status == SpanStatus.ERROR;
    }

    private static String key(TraceView.Row row) {
        return row.kind() + "|" + row.name();
    }

    private record Tree(Map<String, TraceView.Row> byId, Map<String, List<TraceView.Row>> kids,
            List<TraceView.Row> roots) {

        static Tree of(TraceView view) {
            Map<String, TraceView.Row> byId = new HashMap<>();
            view.spans().forEach(r -> byId.put(r.spanId(), r));
            Map<String, List<TraceView.Row>> kids = new HashMap<>();
            List<TraceView.Row> roots = new ArrayList<>();
            // TraceView rows are already in tree order, so children stay sorted by start time.
            for (TraceView.Row r : view.spans()) {
                if (r.parentSpanId() != null && byId.containsKey(r.parentSpanId())) {
                    kids.computeIfAbsent(r.parentSpanId(), k -> new ArrayList<>()).add(r);
                } else {
                    roots.add(r);
                }
            }
            return new Tree(byId, kids, roots);
        }

        List<TraceView.Row> children(TraceView.Row row) {
            return kids.getOrDefault(Objects.requireNonNull(row).spanId(), List.of());
        }
    }
}
