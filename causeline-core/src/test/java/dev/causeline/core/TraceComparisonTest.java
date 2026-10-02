// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.TraceComparison.Change;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceComparisonTest {

    private static final long MS = 1_000_000;

    @Test
    void comparesTheReplayedRequestSubtreeSpanBySpan() {
        // Original: browser action → server request → controller → db + payment (slow, failed).
        TraceView original = view("a1", SpanStatus.ERROR,
                row("u", null, 0, SpanKind.UI_ACTION, "Checkout", 900, SpanStatus.OK, "browser"),
                row("s", "u", 1, SpanKind.REQUEST, "POST /api/orders", 876, SpanStatus.ERROR, "500"),
                row("c", "s", 2, SpanKind.CONTROLLER, "OrderController.createOrder", 870, SpanStatus.ERROR, null),
                row("d", "c", 3, SpanKind.DATABASE, "INSERT orders", 42, SpanStatus.OK, null),
                row("p", "c", 3, SpanKind.HTTP_CLIENT, "POST payment/charge", 810, SpanStatus.ERROR, null),
                row("e", "p", 4, SpanKind.EXCEPTION, "PaymentTimeoutException", 0, SpanStatus.ERROR, null));
        // Replay: same request, payment fast and fine, an extra query appears.
        TraceView replay = view("b1", SpanStatus.OK,
                row("S", null, 0, SpanKind.REQUEST, "POST /api/orders", 104, SpanStatus.OK, "201"),
                row("C", "S", 1, SpanKind.CONTROLLER, "OrderController.createOrder", 100, SpanStatus.OK, null),
                row("D", "C", 2, SpanKind.DATABASE, "INSERT orders", 39, SpanStatus.OK, null),
                row("P", "C", 2, SpanKind.HTTP_CLIENT, "POST payment/charge", 31, SpanStatus.OK, null),
                row("Q", "C", 2, SpanKind.DATABASE, "UPDATE orders", 2, SpanStatus.OK, null));

        TraceComparison.Result result = TraceComparison.compare(original, "s", replay);

        assertThat(result.original().httpStatus()).isEqualTo("500");
        assertThat(result.replay().httpStatus()).isEqualTo("201");
        assertThat(result.replay().status()).isEqualTo(SpanStatus.OK);
        assertThat(result.rows()).extracting(TraceComparison.Row::name, TraceComparison.Row::change).containsExactly(
                org.assertj.core.groups.Tuple.tuple("POST /api/orders", Change.STATUS_CHANGED),
                org.assertj.core.groups.Tuple.tuple("OrderController.createOrder", Change.STATUS_CHANGED),
                org.assertj.core.groups.Tuple.tuple("INSERT orders", Change.SAME),
                org.assertj.core.groups.Tuple.tuple("POST payment/charge", Change.STATUS_CHANGED),
                org.assertj.core.groups.Tuple.tuple("PaymentTimeoutException", Change.ONLY_IN_ORIGINAL),
                org.assertj.core.groups.Tuple.tuple("UPDATE orders", Change.ONLY_IN_REPLAY));
        assertThat(result.rows().getFirst().originalNanos()).isEqualTo(876 * MS);
        assertThat(result.rows().getFirst().replayNanos()).isEqualTo(104 * MS);
    }

    @Test
    void highlightsOnlyLargeRelativeDurationChanges() {
        assertThat(change(100, 70)).isEqualTo(Change.FASTER);
        assertThat(change(100, 130)).isEqualTo(Change.SLOWER);
        assertThat(change(100, 115)).isEqualTo(Change.SAME);   // 15 ms: below 20 ms
        assertThat(change(1000, 1100)).isEqualTo(Change.SAME); // 100 ms but only 10%
    }

    private static Change change(long beforeMs, long afterMs) {
        return TraceComparison.change(
                row("x", null, 0, SpanKind.SERVICE, "op", beforeMs, SpanStatus.OK, null),
                row("y", null, 0, SpanKind.SERVICE, "op", afterMs, SpanStatus.OK, null));
    }

    private static TraceView view(String traceId, SpanStatus status, TraceView.Row... rows) {
        return new TraceView(traceId, rows[0].name(), status, 0, rows[0].durationNanos(), List.of(rows), List.of());
    }

    private static TraceView.Row row(String id, String parent, int depth, SpanKind kind, String name, long ms,
            SpanStatus status, String httpStatus) {
        return new TraceView.Row(id, parent, kind, name, "app", status, depth, 0, ms * MS, 0, false,
                httpStatus == null ? Map.of() : Map.of("http.response.status_code", httpStatus));
    }
}
