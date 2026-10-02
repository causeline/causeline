// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Insight.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InsightsTest {

    private static final long MS = 1_000_000;

    @Test
    void flagsTheSpanWithMostSelfTimeAsPrimaryBottleneck() {
        // The V1.0 example: 876 ms in total, 810 ms of it in the payment call.
        List<TraceView.Row> rows = List.of(
                row("c", null, SpanKind.CONTROLLER, "OrderController.createOrder", 0, 876, 2),
                row("s", "c", SpanKind.SERVICE, "OrderService.createOrder", 2, 874, 22),
                row("r", "s", SpanKind.REPOSITORY, "OrderRepository.save", 4, 42, 42),
                row("p", "s", SpanKind.HTTP_CLIENT, "PaymentClient.charge", 50, 810, 810));

        List<Insight> insights = Insights.analyze(rows, 876 * MS);

        assertThat(insights).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.PRIMARY_BOTTLENECK);
            assertThat(i.spanId()).isEqualTo("p");
            assertThat(i.sharePercent()).isEqualTo(92);
            assertThat(i.label()).isEqualTo("Potential bottleneck: PaymentClient.charge · 810 ms · 92%");
        });
    }

    @Test
    void reportsNotableSpansWhenNothingDominates() {
        List<TraceView.Row> rows = List.of(
                row("a", null, SpanKind.SERVICE, "A", 0, 1000, 400),
                row("b", "a", SpanKind.DATABASE, "SELECT orders", 0, 300, 300),
                row("c", "a", SpanKind.HTTP_CLIENT, "POST pricing", 300, 300, 300));

        assertThat(Insights.analyze(rows, 1000 * MS))
                .extracting(Insight::rule, Insight::spanId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Rule.NOTABLE, "b"),
                        org.assertj.core.groups.Tuple.tuple(Rule.NOTABLE, "c"));
    }

    @Test
    void neverFlagsARootSpanOrASingleSpanTrace() {
        List<TraceView.Row> single = List.of(row("a", null, SpanKind.REQUEST, "PUT /api/demo/settings", 0, 56, 56));

        assertThat(Insights.analyze(single, 56 * MS)).isEmpty();
    }

    @Test
    void ignoresShortSpansEvenWithALargeShare() {
        List<TraceView.Row> rows = List.of(
                row("a", null, SpanKind.SERVICE, "A", 0, 10, 4),
                row("b", "a", SpanKind.DATABASE, "SELECT x", 0, 3, 3),
                row("c", "a", SpanKind.DATABASE, "SELECT y", 3, 3, 3));

        assertThat(Insights.analyze(rows, 10 * MS)).isEmpty();
    }

    @Test
    void detectsRepeatedQueriesUnderOneParent() {
        List<TraceView.Row> rows = new ArrayList<>();
        rows.add(row("r", null, SpanKind.REPOSITORY, "OrderRepository.findAll", 0, 100, 10));
        for (int i = 0; i < 12; i++) {
            rows.add(row("q" + i, "r", SpanKind.DATABASE, "SELECT order_lines", i * 7, 7, 7));
        }

        assertThat(Insights.analyze(rows, 100 * MS))
                .filteredOn(i -> i.rule() == Rule.REPEATED_QUERY)
                .singleElement()
                .satisfies(i -> assertThat(i.label()).startsWith("Repeated query × 12: SELECT order_lines"));
    }

    @Test
    void detectsSlowHandlingOfAResponseInTheBrowser() {
        List<TraceView.Row> rows = List.of(
                row("a", null, SpanKind.UI_ACTION, "Checkout", 0, 600, 250),
                browserRequest("f", "a", 10, 300),
                row("s", "a", SpanKind.STATE_UPDATE, "checkoutResult", 460, 0, 0));

        assertThat(Insights.analyze(rows, 600 * MS))
                .filteredOn(i -> i.rule() == Rule.SLOW_CLIENT_HANDLING)
                .singleElement()
                .satisfies(i -> assertThat(i.label()).contains("150 ms").contains("POST /api/orders"));
    }

    @Test
    void pointsOutHandledExceptionsAndWhereTheyHappened() {
        List<TraceView.Row> rows = List.of(
                row("r", null, SpanKind.REQUEST, "POST /api/orders", 0, 300, 10),
                row("s", "r", SpanKind.SERVICE, "StockService.isInStock", 5, 152, 152),
                new TraceView.Row("e", "s", SpanKind.EXCEPTION, "InventoryUnavailableException", "checkout",
                        SpanStatus.UNSET, 2, 155 * MS, 0, 0, false,
                        Map.of(TraceAssembler.HANDLED_ATTRIBUTE, "true", "log.level", "WARN")));

        assertThat(Insights.analyze(rows, 300 * MS))
                .filteredOn(i -> i.rule() == Rule.HANDLED_EXCEPTION)
                .singleElement()
                .satisfies(i -> assertThat(i.label()).isEqualTo(
                        "Handled exception: InventoryUnavailableException in StockService.isInStock (152 ms), "
                                + "caught and logged at WARN; the request continued"));
    }

    private static TraceView.Row browserRequest(String id, String parent, long offsetMs, long durationMs) {
        return new TraceView.Row(id, parent, SpanKind.REQUEST, "POST /api/orders", "browser", SpanStatus.OK, 1,
                offsetMs * MS, durationMs * MS, 5 * MS, false, Map.of());
    }

    private static TraceView.Row row(String id, String parent, SpanKind kind, String name, long offsetMs,
            long durationMs, long selfMs) {
        return new TraceView.Row(id, parent, kind, name, "checkout", SpanStatus.OK, parent == null ? 0 : 1,
                offsetMs * MS, durationMs * MS, selfMs * MS, false, Map.of());
    }
}
