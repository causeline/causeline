// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceStoreTest {

    @Test
    void groupsSpansByTrace() {
        TraceStore store = new TraceStore();
        store.add(span("0af7651916cd43dd8448eb211c80319c", "00f067aa0ba902b7"));
        store.add(span("0af7651916cd43dd8448eb211c80319c", "00f067aa0ba902b8"));
        store.add(span("1af7651916cd43dd8448eb211c80319c", "00f067aa0ba902b9"));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get("0af7651916cd43dd8448eb211c80319c").orElseThrow()).hasSize(2);
        assertThat(store.get("ffffffffffffffffffffffffffffffff")).isEmpty();
    }

    @Test
    void evictsLeastRecentlyUsedTraceWhenFull() {
        TraceStore store = new TraceStore(2);
        store.add(span("00000000000000000000000000000001", "0000000000000001"));
        store.add(span("00000000000000000000000000000002", "0000000000000002"));
        store.get("00000000000000000000000000000001"); // touch the first trace
        store.add(span("00000000000000000000000000000003", "0000000000000003"));

        assertThat(store.get("00000000000000000000000000000001")).isPresent();
        assertThat(store.get("00000000000000000000000000000002")).isEmpty();
        assertThat(store.get("00000000000000000000000000000003")).isPresent();
    }

    @Test
    void pinnedTracesSurviveEviction() {
        TraceStore store = new TraceStore(2);
        store.add(span("00000000000000000000000000000001", "0000000000000001"));
        store.pin("00000000000000000000000000000001");
        store.add(span("00000000000000000000000000000002", "0000000000000002"));
        store.add(span("00000000000000000000000000000003", "0000000000000003"));
        store.add(span("00000000000000000000000000000004", "0000000000000004"));

        assertThat(store.get("00000000000000000000000000000001")).isPresent();
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.pin("ffffffffffffffffffffffffffffffff")).isFalse();
    }

    @Test
    void evictsOldestTracesWhenTheByteBudgetIsExceeded() {
        Span one = span("00000000000000000000000000000001", "0000000000000001");
        long perTrace = TraceStore.estimateBytes(one);
        TraceStore store = new TraceStore(1_000, perTrace * 2);

        store.add(one);
        store.add(span("00000000000000000000000000000002", "0000000000000002"));
        store.add(span("00000000000000000000000000000003", "0000000000000003"));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get("00000000000000000000000000000001")).isEmpty();
        assertThat(store.estimatedBytes()).isEqualTo(perTrace * 2);
        assertThat(store.evictedTraces()).isEqualTo(1);
    }

    @Test
    void largerSpansCostMore() {
        Span small = span("00000000000000000000000000000001", "0000000000000001");
        Span large = new Span("00000000000000000000000000000001", "0000000000000002", null, SpanKind.SERVICE,
                "op".repeat(500), "test", 0, 1, SpanStatus.OK, Map.of("exception.stacktrace", "x".repeat(8_000)));

        assertThat(TraceStore.estimateBytes(large)).isGreaterThan(TraceStore.estimateBytes(small) + 16_000);
    }

    private static Span span(String traceId, String spanId) {
        return new Span(traceId, spanId, null, SpanKind.REQUEST, "op", "test", 0, 1, SpanStatus.OK, Map.of());
    }
}
