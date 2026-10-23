// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.LoadAggregate.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadAggregateTest {

    private static final long MS = 1_000_000;

    @Test
    void theSpanWhoseSelfTimeGrowsUnderLoadComesFirst() {
        LoadAggregate aggregate = new LoadAggregate();
        for (int i = 0; i < 5; i++) {
            aggregate.add(Phase.BASELINE, checkout(30, 4, false, false));
        }
        for (int i = 0; i < 20; i++) {
            // The payment client waits its turn: 30 ms alone, up to 600 ms with 20 in flight.
            aggregate.add(Phase.LOAD, checkout(30 * (i + 1), 5, false, false));
        }

        LoadAggregate.Report report = aggregate.report();

        assertThat(report.baselineHits()).isEqualTo(5);
        assertThat(report.loadHits()).isEqualTo(20);
        LoadAggregate.Row top = report.rows().getFirst();
        assertThat(top.name()).isEqualTo("PaymentClient.charge");
        assertThat(top.grew()).isTrue();
        assertThat(top.baselineP95()).isEqualTo(30 * MS);
        assertThat(top.loadP95()).isEqualTo(570 * MS); // nearest rank: the 19th of 20
        assertThat(top.loadP50()).isEqualTo(300 * MS);
        // 4 → 5 ms is noise, not growth.
        assertThat(report.rows()).filteredOn(r -> r.name().equals("INSERT orders")).singleElement()
                .satisfies(r -> assertThat(r.grew()).isFalse());
        assertThat(report.rows()).noneMatch(r -> r.kind() == SpanKind.LOG);
    }

    @Test
    void nothingGrowsWhenTheLoadLooksLikeTheBaseline() {
        LoadAggregate aggregate = new LoadAggregate();
        for (int i = 0; i < 5; i++) {
            aggregate.add(Phase.BASELINE, checkout(30, 4, false, false));
        }
        for (int i = 0; i < 20; i++) {
            aggregate.add(Phase.LOAD, checkout(32, 5, false, false));
        }
        assertThat(aggregate.report().rows()).noneMatch(LoadAggregate.Row::grew);
    }

    @Test
    void spansAndExceptionsSeenInSomeHitsOnlyAreCounted() {
        LoadAggregate aggregate = new LoadAggregate();
        aggregate.add(Phase.BASELINE, checkout(30, 4, false, false));
        aggregate.add(Phase.LOAD, checkout(30, 4, true, false));
        aggregate.add(Phase.LOAD, checkout(30, 4, true, true));
        aggregate.add(Phase.LOAD, checkout(30, 4, false, false));

        LoadAggregate.Report report = aggregate.report();

        LoadAggregate.Row retry = report.rows().stream().filter(r -> r.name().equals("SELECT stock")).findFirst().orElseThrow();
        assertThat(retry.baselineHits()).isZero();
        assertThat(retry.loadHits()).isEqualTo(2);
        assertThat(retry.deltaP95()).isNull();
        assertThat(retry.grew()).isFalse();
        assertThat(report.rows().getLast().name()).isIn("SELECT stock", "OptimisticLockException");
        assertThat(report.baselineDatabaseSpans()).isEqualTo(1.0);
        assertThat(report.loadDatabaseSpans()).isEqualTo(2.0);
        assertThat(report.failures()).singleElement().satisfies(f -> {
            assertThat(f.exceptionType()).isEqualTo("jakarta.persistence.OptimisticLockException");
            assertThat(f.codeLocation()).isEqualTo("OrderService.java:42");
            assertThat(f.baselineHits()).isZero();
            assertThat(f.loadHits()).isEqualTo(1);
        });
    }

    @Test
    void theReportCarriesNoCapturedValues() {
        LoadAggregate aggregate = new LoadAggregate();
        aggregate.add(Phase.BASELINE, checkout(30, 4, false, true));
        aggregate.add(Phase.LOAD, checkout(30, 4, true, true));

        assertThat(aggregate.report().toString()).doesNotContain("hunter2").doesNotContain("4111");
    }

    @Test
    void aTraceWithoutItsServerRequestIsIgnored() {
        LoadAggregate aggregate = new LoadAggregate();
        aggregate.add(Phase.LOAD, new TraceView("t", "x", SpanStatus.OK, 0, 0,
                List.of(row("c", null, 0, SpanKind.CONTROLLER, "OrderController.createOrder", 10, Map.of())), List.of()));
        assertThat(aggregate.report().loadHits()).isZero();
        assertThat(aggregate.report().rows()).isEmpty();
    }

    @Test
    void percentilesUseTheNearestRank() {
        List<Long> values = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(LoadAggregate.percentile(values, 0.50)).isEqualTo(5L);
        assertThat(LoadAggregate.percentile(values, 0.95)).isEqualTo(10L);
        assertThat(LoadAggregate.percentile(List.of(7L), 0.99)).isEqualTo(7L);
        assertThat(LoadAggregate.percentile(List.of(), 0.5)).isNull();
    }

    /** POST /api/orders → controller → INSERT + PaymentClient.charge (+ a retry query and an exception). */
    private static TraceView checkout(long paymentSelfMs, long insertMs, boolean retry, boolean exception) {
        List<TraceView.Row> rows = new ArrayList<>();
        // The request's parent is the replay's traceparent, which is not part of the trace.
        rows.add(row("r", "ffffffffffffffff", 0, SpanKind.REQUEST, "POST /api/orders", 2,
                Map.of("http.request.body", "{\"password\":\"hunter2\"}")));
        rows.add(row("c", "r", 1, SpanKind.CONTROLLER, "OrderController.createOrder", 1, Map.of()));
        rows.add(row("d", "c", 2, SpanKind.DATABASE, "INSERT orders", insertMs,
                Map.of("db.query.text", "INSERT INTO orders VALUES ('4111')")));
        rows.add(row("p", "c", 2, SpanKind.SERVICE, "PaymentClient.charge", paymentSelfMs, Map.of()));
        rows.add(row("l", "c", 2, SpanKind.LOG, "INFO Order paid", 0, Map.of("log.message", "card 4111")));
        if (retry) {
            rows.add(row("q", "c", 2, SpanKind.DATABASE, "SELECT stock", 3, Map.of()));
        }
        if (exception && retry) {
            rows.add(row("e", "c", 2, SpanKind.EXCEPTION, "OptimisticLockException", 0,
                    Map.of("exception.type", "jakarta.persistence.OptimisticLockException",
                            "code.location", "OrderService.java:42", "exception.message", "hunter2")));
        }
        return new TraceView("t", "POST /api/orders", SpanStatus.OK, 0, rows.getFirst().durationNanos(), rows, List.of());
    }

    private static TraceView.Row row(String id, String parent, int depth, SpanKind kind, String name, long selfMs,
            Map<String, String> attributes) {
        return new TraceView.Row(id, parent, kind, name, "app", SpanStatus.OK, depth, 0, selfMs * MS, selfMs * MS, false,
                attributes);
    }
}
