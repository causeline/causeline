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
                .satisfies(i -> assertThat(i.label())
                        .startsWith("N+1 query: SELECT order_lines ran 12 times in OrderRepository.findAll")
                        .contains("JOIN FETCH"));
    }

    @Test
    void namesTheQueryWhoseRowsTheRepeatedSelectsLoad() {
        List<TraceView.Row> rows = new ArrayList<>();
        rows.add(row("s", null, SpanKind.SERVICE, "OrderService.list", 0, 100, 10));
        rows.add(row("o", "s", SpanKind.DATABASE, "SELECT orders", 0, 4, 4));
        for (int i = 0; i < 5; i++) {
            rows.add(row("q" + i, "s", SpanKind.DATABASE, "SELECT order_lines", 5 + i * 7, 7, 7));
        }
        // Four repeats is not yet a pattern.
        assertThat(Insights.analyze(rows.subList(0, 6), 100 * MS)).noneMatch(i -> i.rule() == Rule.REPEATED_QUERY);

        assertThat(Insights.analyze(rows, 100 * MS))
                .filteredOn(i -> i.rule() == Rule.REPEATED_QUERY)
                .singleElement()
                .satisfies(i -> assertThat(i.label()).contains("ran 5 times in OrderService.list, once per row of SELECT orders"));
    }

    @Test
    void repeatedWritesSuggestBatching() {
        List<TraceView.Row> rows = new ArrayList<>();
        rows.add(row("s", null, SpanKind.SERVICE, "ImportService.run", 0, 100, 10));
        for (int i = 0; i < 10; i++) {
            rows.add(row("q" + i, "s", SpanKind.DATABASE, "INSERT items", i * 7, 7, 7));
        }
        assertThat(Insights.analyze(rows, 100 * MS))
                .filteredOn(i -> i.rule() == Rule.REPEATED_QUERY)
                .singleElement()
                .satisfies(i -> assertThat(i.label()).startsWith("Repeated query × 10: INSERT items in ImportService.run"));
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

    @Test
    void flagsWritesOfOneRequestCommittedInSeparateTransactions() {
        // A service without @Transactional: each repository call gets its own transaction.
        List<TraceView.Row> rows = List.of(
                row("q", null, SpanKind.REQUEST, "POST /api/orders", 0, 100, 10),
                row("s", "q", SpanKind.SERVICE, "OrderService.createOrder", 1, 90, 10),
                row("t1", "s", SpanKind.TRANSACTION, "Transaction SimpleJpaRepository.save", 2, 10, 1),
                row("i", "t1", SpanKind.DATABASE, "INSERT orders", 3, 5, 5),
                row("t2", "s", SpanKind.TRANSACTION, "Transaction SimpleJpaRepository.save", 20, 10, 1),
                row("u", "t2", SpanKind.DATABASE, "UPDATE stock", 21, 5, 5),
                // Handed to another thread and still running after the request: its own transaction is expected.
                row("a", "s", SpanKind.SERVICE, "AuditLog.record", 80, 200, 1),
                row("ai", "a", SpanKind.DATABASE, "INSERT audit", 250, 5, 5));

        assertThat(Insights.analyze(rows, 100 * MS))
                .filteredOn(i -> i.rule() == Rule.WRITES_NOT_ATOMIC)
                .singleElement().satisfies(i -> {
                    assertThat(i.spanId()).isEqualTo("i");
                    assertThat(i.label()).startsWith("Writes not in one transaction: 2 statements (INSERT orders, UPDATE stock)"
                            + " were committed separately in POST /api/orders.");
                });
    }

    @Test
    void writesInsideOneTransactionAreFineIncludingReactiveOnesThatDoNotNest() {
        List<TraceView.Row> nested = List.of(
                row("q", null, SpanKind.REQUEST, "POST /api/orders", 0, 100, 10),
                row("t", "q", SpanKind.TRANSACTION, "Transaction OrderService.createOrder", 1, 90, 1),
                row("i", "t", SpanKind.DATABASE, "INSERT orders", 3, 5, 5),
                row("r", "t", SpanKind.REPOSITORY, "OrderRepository.save", 20, 10, 1),
                row("u", "r", SpanKind.DATABASE, "UPDATE orders", 21, 5, 5));
        // Reactive: the transaction is a sibling of the work done in it, under the span that opened it.
        List<TraceView.Row> reactive = List.of(
                row("q", null, SpanKind.REQUEST, "POST /api/orders", 0, 100, 10),
                row("s", "q", SpanKind.SERVICE, "OrderService.createOrder", 1, 95, 1),
                row("t", "s", SpanKind.TRANSACTION, "Transaction OrderService.createOrder", 2, 90, 1),
                row("r", "s", SpanKind.REPOSITORY, "OrderRepository.save", 5, 10, 1),
                row("i", "r", SpanKind.DATABASE, "INSERT orders", 6, 5, 5),
                row("u", "s", SpanKind.DATABASE, "UPDATE orders", 30, 5, 5));

        assertThat(Insights.analyze(nested, 100 * MS)).isEmpty();
        assertThat(Insights.analyze(reactive, 100 * MS)).isEmpty();
        // One statement that commits by itself is nothing to flag.
        assertThat(Insights.analyze(List.of(
                row("q", null, SpanKind.REQUEST, "POST /api/orders", 0, 100, 90),
                row("i", "q", SpanKind.DATABASE, "INSERT orders", 3, 5, 5)), 100 * MS)).isEmpty();
    }

    @Test
    void flagsLazyLoadingInTheControllerAfterTheTransactionEnded() {
        List<TraceView.Row> rows = List.of(
                row("q", null, SpanKind.REQUEST, "GET /api/orders", 0, 100, 10),
                row("c", "q", SpanKind.CONTROLLER, "OrderController.list", 1, 98, 40),
                row("s", "c", SpanKind.SERVICE, "OrderService.findAll", 2, 30, 1),
                row("t", "s", SpanKind.TRANSACTION, "Transaction OrderService.findAll", 3, 28, 1),
                row("all", "t", SpanKind.DATABASE, "SELECT orders", 4, 5, 5),
                row("l1", "c", SpanKind.DATABASE, "SELECT order_lines", 40, 2, 2),
                row("l2", "c", SpanKind.DATABASE, "SELECT order_lines", 45, 2, 2),
                row("l3", "q", SpanKind.DATABASE, "SELECT customers", 99, 1, 1));

        assertThat(Insights.analyze(rows, 100 * MS))
                .filteredOn(i -> i.rule() == Rule.QUERY_AFTER_TRANSACTION)
                .singleElement().satisfies(i -> {
                    assertThat(i.spanId()).isEqualTo("l1");
                    assertThat(i.label()).startsWith("Lazy loading after the transaction: 3 queries (SELECT order_lines,"
                            + " SELECT customers) ran in OrderController.list after Transaction OrderService.findAll had ended");
                });
        // A query in a repository after a transaction is an ordinary query, not lazy loading in the view.
        assertThat(Insights.analyze(List.of(
                row("q", null, SpanKind.REQUEST, "GET /api/orders", 0, 100, 10),
                row("t", "q", SpanKind.TRANSACTION, "Transaction OrderService.findAll", 3, 28, 1),
                row("r", "q", SpanKind.REPOSITORY, "PriceRepository.findAll", 40, 10, 1),
                row("p", "r", SpanKind.DATABASE, "SELECT prices", 41, 5, 5)), 100 * MS)).isEmpty();
    }

    @Test
    void flagsATransactionHeldOpenAcrossAnHttpCall() {
        List<TraceView.Row> rows = List.of(
                row("q", null, SpanKind.REQUEST, "POST /api/orders", 0, 1000, 10),
                row("t", "q", SpanKind.TRANSACTION, "Transaction OrderService.createOrder", 5, 900, 10),
                row("i", "t", SpanKind.DATABASE, "INSERT orders", 6, 5, 5),
                row("p", "t", SpanKind.SERVICE, "PaymentClient.charge", 20, 810, 5),
                row("h", "p", SpanKind.HTTP_CLIENT, "POST localhost/fake-payment/charge", 22, 800, 800));

        assertThat(Insights.analyze(rows, 1000 * MS))
                .filteredOn(i -> i.rule() == Rule.TRANSACTION_ACROSS_CALL)
                .singleElement().satisfies(i -> {
                    assertThat(i.spanId()).isEqualTo("t");
                    assertThat(i.label()).startsWith("Transaction open during an HTTP call: Transaction"
                            + " OrderService.createOrder lasted 900 ms, 800 ms of it waiting for POST localhost/fake-payment/charge.");
                });
        // A quick call, or one made outside the transaction, is left alone.
        List<TraceView.Row> quick = List.of(
                row("q", null, SpanKind.REQUEST, "POST /api/orders", 0, 1000, 10),
                row("t", "q", SpanKind.TRANSACTION, "Transaction OrderService.createOrder", 5, 100, 10),
                row("h", "t", SpanKind.HTTP_CLIENT, "POST pricing", 22, 40, 40),
                row("h2", "q", SpanKind.HTTP_CLIENT, "POST payment", 200, 700, 700));
        assertThat(Insights.analyze(quick, 1000 * MS)).noneMatch(i -> i.rule() == Rule.TRANSACTION_ACROSS_CALL);
    }

    @Test
    void flagsGetRequestsThatRanOneAfterAnotherInOneAction() {
        List<TraceView.Row> rows = List.of(
                row("a", null, SpanKind.UI_ACTION, "Open dashboard", 0, 700, 10),
                browser("r1", "a", "GET /api/user", 5, 200),
                browser("r2", "a", "GET /api/orders", 210, 300),
                browser("r3", "a", "GET /api/prices", 515, 140),
                // Started while r3 was running: already parallel.
                browser("r4", "a", "GET /api/flags", 520, 50));

        assertThat(Insights.analyze(rows, 700 * MS))
                .filteredOn(i -> i.rule() == Rule.REQUEST_WATERFALL)
                .singleElement().satisfies(i -> {
                    assertThat(i.spanId()).isEqualTo("r1");
                    assertThat(i.label()).isEqualTo("Request waterfall: 3 requests ran one after another in Open dashboard"
                            + " (GET /api/user, GET /api/orders, GET /api/prices), 640 ms in total. If none needs the"
                            + " previous one's answer, start them together (Promise.all): about 300 ms");
                });
    }

    @Test
    void doesNotCallItAWaterfallWhenRequestsChangeDataOverlapOrAreFarApart() {
        List<TraceView.Row> rows = List.of(
                row("a", null, SpanKind.UI_ACTION, "Checkout", 0, 2000, 10),
                browser("p", "a", "POST /api/orders", 5, 300),
                browser("g", "a", "GET /api/orders/1", 310, 200),
                // The user did something else in between.
                browser("late", "a", "GET /api/prices", 1500, 200),
                // Server-side requests are not the browser's to parallelise.
                row("s1", "a", SpanKind.REQUEST, "GET /a", 600, 100, 100),
                row("s2", "a", SpanKind.REQUEST, "GET /b", 700, 100, 100));

        assertThat(Insights.analyze(rows, 2000 * MS)).noneMatch(i -> i.rule() == Rule.REQUEST_WATERFALL);
    }

    @Test
    void flagsAComponentThatRendersFiveTimesInOneAction() {
        List<TraceView.Row> rows = new ArrayList<>();
        rows.add(row("a", null, SpanKind.UI_ACTION, "Checkout", 0, 500, 10));
        for (int i = 0; i < 5; i++) {
            rows.add(row("c" + i, "a", SpanKind.RENDER, "CheckoutPage", 10 + i * 10, 4, 4));
        }
        for (int i = 0; i < 4; i++) {
            rows.add(row("h" + i, "a", SpanKind.RENDER, "Header", 10 + i * 10, 1, 1));
        }

        assertThat(Insights.analyze(rows, 500 * MS))
                .filteredOn(i -> i.rule() == Rule.RENDER_STORM)
                .singleElement().satisfies(i -> {
                    assertThat(i.spanId()).isEqualTo("c0");
                    assertThat(i.label()).startsWith("Re-render storm: CheckoutPage rendered 5 times during Checkout (20 ms of rendering).");
                });
    }

    @Test
    void flagsStateSetAfterItsComponentUnmountedOncePerState() {
        List<TraceView.Row> rows = List.of(
                row("a", null, SpanKind.UI_ACTION, "Checkout", 0, 500, 400),
                row("ok", "a", SpanKind.STATE_UPDATE, "status", 10, 0, 0),
                state("late1", "a", "status", 300),
                state("late2", "a", "status", 310));

        assertThat(Insights.analyze(rows, 500 * MS))
                .filteredOn(i -> i.rule() == Rule.STATE_UPDATE_AFTER_UNMOUNT)
                .singleElement().satisfies(i -> {
                    assertThat(i.spanId()).isEqualTo("late1");
                    assertThat(i.label()).startsWith("State update after unmount: status was set after its component had unmounted.");
                });
    }

    private static TraceView.Row browser(String id, String parent, String name, long offsetMs, long durationMs) {
        return new TraceView.Row(id, parent, SpanKind.REQUEST, name, "browser", SpanStatus.OK, 1, offsetMs * MS,
                durationMs * MS, durationMs * MS, false, Map.of("http.request.method", name.substring(0, name.indexOf(' '))));
    }

    private static TraceView.Row state(String id, String parent, String name, long offsetMs) {
        return new TraceView.Row(id, parent, SpanKind.STATE_UPDATE, name, "browser", SpanStatus.OK, 1, offsetMs * MS, 0, 0,
                false, Map.of(Insights.AFTER_UNMOUNT_ATTRIBUTE, "true"));
    }

    private static TraceView.Row row(String id, String parent, SpanKind kind, String name, long offsetMs,
            long durationMs, long selfMs) {
        return new TraceView.Row(id, parent, kind, name, "checkout", SpanStatus.OK, parent == null ? 0 : 1,
                offsetMs * MS, durationMs * MS, selfMs * MS, false, Map.of());
    }
}
