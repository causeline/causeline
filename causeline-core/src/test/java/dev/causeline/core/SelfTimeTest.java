// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SelfTimeTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    @Test
    void spanWithoutChildrenIsAllSelfTime() {
        Span parent = span("00f067aa0ba902b7", null, 0, 876);

        assertThat(SelfTime.selfTimeNanos(parent, List.of())).isEqualTo(876);
    }

    @Test
    void sequentialChildrenAreSubtracted() {
        Span controller = span("00f067aa0ba902b7", null, 0, 876);
        Span service = span("1111111111111111", "00f067aa0ba902b7", 2, 52);
        Span payment = span("2222222222222222", "00f067aa0ba902b7", 54, 820);

        assertThat(SelfTime.selfTimeNanos(controller, List.of(service, payment))).isEqualTo(4);
    }

    @Test
    void overlappingChildrenAreCountedOnce() {
        Span parent = span("00f067aa0ba902b7", null, 0, 100);
        Span first = span("1111111111111111", "00f067aa0ba902b7", 10, 50);
        Span parallel = span("2222222222222222", "00f067aa0ba902b7", 30, 50);

        // Children cover 10..80 together, so 30 of the 100 remain.
        assertThat(SelfTime.selfTimeNanos(parent, List.of(first, parallel))).isEqualTo(30);
    }

    @Test
    void childOverrunningParentIsClippedToParentWindow() {
        Span parent = span("00f067aa0ba902b7", null, 0, 100);
        Span skewed = span("1111111111111111", "00f067aa0ba902b7", 60, 500);

        assertThat(SelfTime.selfTimeNanos(parent, List.of(skewed))).isEqualTo(60);
    }

    private static Span span(String id, String parent, long start, long duration) {
        return new Span(TRACE, id, parent, SpanKind.SERVICE, "op", "test", start, duration,
                SpanStatus.OK, Map.of());
    }
}
