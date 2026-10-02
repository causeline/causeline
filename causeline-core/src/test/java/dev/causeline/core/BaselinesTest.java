// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BaselinesTest {

    @Test
    void usualDurationIsTheMedianOfTheOtherSuccessfulRuns() {
        List<TraceSummary> out = Baselines.apply(List.of(
                run("a", "Checkout", 100), run("b", "Checkout", 120), run("c", "Checkout", 110),
                run("d", "Checkout", 400)));

        TraceSummary slow = out.get(3);
        assertThat(slow.baselineNanos()).isEqualTo(110L);
        assertThat(slow.baselineRuns()).isEqualTo(3);
        // b's others are 100, 110 and 400: the slow run barely moves a median.
        assertThat(out.get(1).baselineNanos()).isEqualTo(110L);
    }

    @Test
    void tooFewRunsGiveNoBaseline() {
        List<TraceSummary> out = Baselines.apply(List.of(run("a", "Checkout", 100), run("b", "Checkout", 900)));

        assertThat(out).allSatisfy(s -> assertThat(s.baselineNanos()).isNull());
    }

    @Test
    void failuresReplaysAndOtherActionsDoNotCount() {
        TraceSummary failed = new TraceSummary("x", "Checkout", SpanStatus.ERROR, 0, 5_000, 1, null, false);
        TraceSummary replay = run("y", "Checkout", 5_000).withReplayOf("a");
        List<TraceSummary> out = Baselines.apply(List.of(
                run("a", "Checkout", 100), run("b", "Checkout", 100), failed, replay,
                run("c", "Search", 100), run("d", "Search", 100), run("e", "Checkout", 300)));

        // e is compared with a and b only (the failure, the replay and Search don't count): too few.
        assertThat(out.get(6).baselineNanos()).isNull();
        // The failed run itself is still compared with the three successful Checkout runs.
        assertThat(out.get(2).baselineNanos()).isEqualTo(100L);
    }

    private static TraceSummary run(String id, String name, long durationNanos) {
        return new TraceSummary(id, name, SpanStatus.OK, 0, durationNanos, 3, null, false);
    }
}
