// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the spans of one trace into a {@link TraceView}.
 *
 * <p>Browser and server clocks differ, so timestamps from different sources are never compared
 * directly. When a span's parent was recorded by another source, the child's whole subtree is
 * shifted onto the parent's clock and centred inside the parent (PRD section 4). Timestamps
 * within one source are never adjusted.
 */
public final class TraceAssembler {

    /** Marks an EXCEPTION span for an exception the application caught and logged. */
    public static final String HANDLED_ATTRIBUTE = "causeline.exception.handled";

    private TraceAssembler() {
    }

    public static TraceView assemble(String traceId, Collection<Span> spans) {
        if (spans.isEmpty()) {
            throw new IllegalArgumentException("trace " + traceId + " has no spans");
        }
        Map<String, Span> byId = new HashMap<>();
        withoutLoggedDuplicates(spans).forEach(s -> byId.put(s.spanId(), s));

        Map<String, List<Span>> children = new HashMap<>();
        List<Span> roots = new ArrayList<>();
        for (Span span : byId.values()) {
            // A span whose parent hasn't arrived (or never will) is shown as a root.
            if (span.parentSpanId() == null || !byId.containsKey(span.parentSpanId())) {
                roots.add(span);
            } else {
                children.computeIfAbsent(span.parentSpanId(), k -> new ArrayList<>()).add(span);
            }
        }
        Comparator<Span> byStart = Comparator.comparingLong(Span::startTimeUnixNano);
        roots.sort(byStart);
        children.values().forEach(list -> list.sort(byStart));

        // Shift per span, accumulated down the tree so a moved subtree moves as a whole.
        Map<String, Long> shift = new HashMap<>();
        Set<String> skewed = new HashSet<>();
        for (Span root : roots) {
            place(root, 0, children, shift, skewed);
        }

        long traceStart = Long.MAX_VALUE;
        long traceEnd = Long.MIN_VALUE;
        for (Span span : byId.values()) {
            long start = span.startTimeUnixNano() + shift.get(span.spanId());
            traceStart = Math.min(traceStart, start);
            traceEnd = Math.max(traceEnd, start + span.durationNanos());
        }

        List<TraceView.Row> rows = new ArrayList<>();
        for (Span root : roots) {
            addRows(root, 0, traceStart, children, shift, skewed, rows);
        }

        Span primary = roots.getFirst();
        SpanStatus status = byId.values().stream().anyMatch(s -> s.status() == SpanStatus.ERROR)
                ? SpanStatus.ERROR
                : SpanStatus.OK;
        long duration = traceEnd - traceStart;
        return new TraceView(traceId, primary.name(), status, traceStart, duration, rows,
                Insights.analyze(rows, duration));
    }

    /**
     * Code that logs an exception and then rethrows it produces both a "logged" and a "thrown"
     * record of the same exception. Only the thrown one is kept: it is what actually happened.
     */
    private static List<Span> withoutLoggedDuplicates(Collection<Span> spans) {
        Set<String> thrown = new HashSet<>();
        for (Span span : spans) {
            if (span.kind() == SpanKind.EXCEPTION && !isHandled(span)) {
                thrown.add(exceptionKey(span));
            }
        }
        return spans.stream()
                .filter(s -> !(s.kind() == SpanKind.EXCEPTION && isHandled(s) && thrown.contains(exceptionKey(s))))
                .toList();
    }

    private static boolean isHandled(Span span) {
        return "true".equals(span.attributes().get(HANDLED_ATTRIBUTE));
    }

    private static String exceptionKey(Span span) {
        return span.attributes().get("exception.type") + "|" + span.attributes().get("code.location");
    }

    private static void place(Span span, long inheritedShift, Map<String, List<Span>> children,
            Map<String, Long> shift, Set<String> skewed) {
        shift.put(span.spanId(), inheritedShift);
        for (Span child : children.getOrDefault(span.spanId(), List.of())) {
            long childShift = inheritedShift;
            if (!child.source().equals(span.source())) {
                long parentStart = span.startTimeUnixNano() + inheritedShift;
                long target;
                if (child.durationNanos() <= span.durationNanos()) {
                    target = parentStart + (span.durationNanos() - child.durationNanos()) / 2;
                } else {
                    target = parentStart;
                    skewed.add(child.spanId());
                }
                childShift = target - child.startTimeUnixNano();
            }
            place(child, childShift, children, shift, skewed);
        }
    }

    private static void addRows(Span span, int depth, long traceStart, Map<String, List<Span>> children,
            Map<String, Long> shift, Set<String> skewed, List<TraceView.Row> rows) {
        List<Span> kids = children.getOrDefault(span.spanId(), List.of());
        long spanShift = shift.get(span.spanId());
        // Self time is computed on one clock: children are moved onto the parent's clock first.
        List<Span> kidsOnParentClock = kids.stream()
                .map(k -> withStart(k, k.startTimeUnixNano() + shift.get(k.spanId()) - spanShift))
                .toList();
        rows.add(new TraceView.Row(
                span.spanId(),
                span.parentSpanId(),
                span.kind(),
                span.name(),
                span.source(),
                span.status(),
                depth,
                span.startTimeUnixNano() + spanShift - traceStart,
                span.durationNanos(),
                SelfTime.selfTimeNanos(span, kidsOnParentClock),
                skewed.contains(span.spanId()),
                span.attributes()));
        for (Span kid : kids) {
            addRows(kid, depth + 1, traceStart, children, shift, skewed, rows);
        }
    }

    private static Span withStart(Span s, long start) {
        return new Span(s.traceId(), s.spanId(), s.parentSpanId(), s.kind(), s.name(), s.source(),
                start, s.durationNanos(), s.status(), s.attributes());
    }
}
