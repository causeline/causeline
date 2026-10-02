// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Computes a span's self time: its inclusive duration minus the time covered by its children.
 * Children are merged as a union of intervals, so parallel children are not subtracted twice.
 */
public final class SelfTime {

    private SelfTime() {
    }

    public static long selfTimeNanos(Span parent, Collection<Span> children) {
        long parentStart = parent.startTimeUnixNano();
        long parentEnd = parent.endTimeUnixNano();

        // Only the part of each child inside the parent's window counts; a child that
        // overruns its parent (clock skew, async work) must not push self time below zero.
        List<long[]> intervals = children.stream()
                .map(c -> new long[] {
                        Math.max(c.startTimeUnixNano(), parentStart),
                        Math.min(c.endTimeUnixNano(), parentEnd)})
                .filter(i -> i[1] > i[0])
                .sorted(Comparator.comparingLong(i -> i[0]))
                .toList();

        long covered = 0;
        long runStart = Long.MIN_VALUE;
        long runEnd = Long.MIN_VALUE;
        for (long[] interval : intervals) {
            if (interval[0] > runEnd) {
                covered += runEnd - runStart;
                runStart = interval[0];
                runEnd = interval[1];
            } else {
                runEnd = Math.max(runEnd, interval[1]);
            }
        }
        covered += runEnd - runStart;

        return parent.durationNanos() - covered;
    }
}
