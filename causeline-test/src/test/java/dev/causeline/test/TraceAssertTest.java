// SPDX-License-Identifier: Apache-2.0
package dev.causeline.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceAssertTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final long MS = 1_000_000;

    private int nextId = 1;

    @Test
    void aCleanTracePassesEveryCheck() {
        TraceAssert trace = new TraceAssert(checkout());

        trace.hasNoFailedSpans()
                .hasQueryCount(2)
                .hasQueryCountAtMost(5)
                .hasNoRepeatedQueries()
                .hasSpan("OrderService.createOrder")
                .hasSpans("OrderRepository.save", "OrderController.createOrder")
                .hasSpansInOrder("POST /api/orders", "OrderService.createOrder", "INSERT orders")
                .hasNoSpan("OrderRepository.findAll");
        assertThat(trace.name()).isEqualTo("POST /api/orders");
    }

    @Test
    void queryCountFailuresListTheQueriesAndTheTrace() {
        TraceAssert trace = new TraceAssert(checkout());

        assertThatThrownBy(() -> trace.hasQueryCountAtMost(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected at most 1 SQL query but 2 ran:")
                .hasMessageContaining("  INSERT orders")
                .hasMessageContaining("    CONTROLLER OrderController.createOrder");
        assertThatThrownBy(() -> trace.hasQueryCount(3)).hasMessageContaining("Expected 3 SQL queries but 2 ran:");
    }

    @Test
    void fiveIdenticalSelectsUnderOneParentAreAnNPlusOne() {
        List<Span> spans = checkout();
        String service = spans.get(2).spanId();
        for (int i = 0; i < 5; i++) {
            spans.add(span(service, SpanKind.DATABASE, "SELECT order_lines", 20 + i, 1, SpanStatus.OK));
        }

        assertThatThrownBy(() -> new TraceAssert(spans).hasNoRepeatedQueries())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("N+1 query: SELECT order_lines ran 5 times in OrderService.createOrder");
    }

    @Test
    void failedSpansAndHandledExceptionsBothFailTheGuard() {
        List<Span> failed = checkout();
        failed.add(span(failed.get(2).spanId(), SpanKind.HTTP_CLIENT, "POST /charge", 30, 5, SpanStatus.ERROR));
        assertThatThrownBy(() -> new TraceAssert(failed).hasNoFailedSpans())
                .hasMessageContaining("Expected no failed spans but found 1:")
                .hasMessageContaining("HTTP_CLIENT POST /charge [ERROR]");

        // Caught and logged: status UNSET, so only the span's kind gives it away.
        List<Span> handled = checkout();
        handled.add(span(handled.get(2).spanId(), SpanKind.EXCEPTION, "InventoryUnavailableException", 30, 0,
                SpanStatus.UNSET));
        TraceAssert trace = new TraceAssert(handled);
        trace.hasException("InventoryUnavailableException");
        assertThatThrownBy(trace::hasNoFailedSpans).hasMessageContaining("EXCEPTION InventoryUnavailableException");
        assertThatThrownBy(() -> trace.hasException("PaymentTimeoutException"))
                .hasMessageContaining("but the trace had [InventoryUnavailableException]");
    }

    @Test
    void missingMisorderedAndUnwantedSpansAreReported() {
        TraceAssert trace = new TraceAssert(checkout());

        assertThatThrownBy(() -> trace.hasSpans("OrderService.createOrder", "PaymentClient.charge"))
                .hasMessageContaining("Expected spans that are not in the trace:")
                .hasMessageContaining("  PaymentClient.charge");
        assertThatThrownBy(() -> trace.hasSpansInOrder("OrderService.createOrder", "OrderController.createOrder"))
                .hasMessageContaining("\"OrderController.createOrder\" was not found after the ones before it");
        assertThatThrownBy(() -> trace.hasNoSpan("OrderRepository.save")).hasMessageContaining("the trace has one");
    }

    @Test
    void onlyTracesRecordedAfterTheStartAreSeen() {
        TraceStore store = new TraceStore();
        store.addAll(checkout());
        RecordedTraces recorded = new RecordedTraces(store, () -> { }).within(Duration.ofMillis(200));

        assertThatThrownBy(recorded::trace).hasMessageContaining("No trace was recorded within 200 ms");

        String second = "5bf92f3577b34da6a3ce929d0e0e4736";
        String third = "6bf92f3577b34da6a3ce929d0e0e4736";
        store.add(new Span(second, "a000000000000001", null, SpanKind.REQUEST, "GET /api/orders", "app", 5, MS,
                SpanStatus.OK, Map.of()));
        recorded.trace().hasSpan("GET /api/orders");

        store.add(new Span(third, "a000000000000002", null, SpanKind.REQUEST, "GET /api/prices", "app", 9, MS,
                SpanStatus.OK, Map.of()));
        assertThatThrownBy(recorded::trace)
                .hasMessageContaining("Expected one trace but 2 were recorded: [GET /api/orders, GET /api/prices]");
        recorded.trace("GET /api/prices").hasQueryCount(0);

        recorded.reset();
        assertThat(recorded.traces()).isEmpty();
    }

    @Test
    void aTraceWithoutItsRootIsWaitedFor() {
        TraceStore store = new TraceStore();
        List<Span> spans = checkout();
        List<Span> arrivals = new ArrayList<>(spans.reversed());
        // Children end, and are stored, before their parents: one more span arrives on every flush.
        boolean[] requestSent = {false};
        RecordedTraces recorded = new RecordedTraces(store, () -> {
            if (requestSent[0] && !arrivals.isEmpty()) {
                store.add(arrivals.removeFirst());
            }
        });
        requestSent[0] = true;

        recorded.trace().hasQueryCount(2).hasSpan("POST /api/orders");
    }

    @Test
    void failureMessagesShowSqlAndExceptionLocationsButNoCapturedValues() {
        List<Span> spans = checkout();
        String repository = spans.get(3).spanId();
        spans.add(new Span(TRACE, "%016x".formatted(nextId++), repository, SpanKind.DATABASE, "SELECT orders", "app", 9 * MS, MS,
                SpanStatus.OK, Map.of(
                        "db.query.statement", "select o.id from orders o\n  where o.email = ? and o.pin = 'SECRET-PIN'",
                        "db.query.text", "select o.id from orders o where o.email = 'ann@example.com' and o.pin = 'SECRET-PIN'",
                        "db.query.parameters", "(ann@example.com)")));
        spans.add(new Span(TRACE, "%016x".formatted(nextId++), spans.get(2).spanId(), SpanKind.EXCEPTION,
                "PaymentTimeoutException", "app", 40 * MS, 0, SpanStatus.ERROR, Map.of(
                        "code.location", "PaymentClient.java:38",
                        "exception.message", "card 4111-SECRET declined")));

        assertThatThrownBy(() -> new TraceAssert(spans).hasQueryCount(0))
                .hasMessageContaining("  SELECT orders: select o.id from orders o where o.email = ? and o.pin = ?")
                .hasMessageContaining("DATABASE SELECT orders: select o.id from orders o where o.email = ? and o.pin = ?")
                .hasMessageContaining("EXCEPTION PaymentTimeoutException [ERROR] at PaymentClient.java:38")
                // Build logs leave the machine: nothing captured from the request may be in them.
                .hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("ann@example.com");
    }

    /** REQUEST → CONTROLLER → SERVICE → REPOSITORY → two INSERTs. */
    private List<Span> checkout() {
        List<Span> spans = new ArrayList<>();
        spans.add(span(null, SpanKind.REQUEST, "POST /api/orders", 0, 100, SpanStatus.OK));
        spans.add(span(spans.get(0).spanId(), SpanKind.CONTROLLER, "OrderController.createOrder", 1, 98, SpanStatus.OK));
        spans.add(span(spans.get(1).spanId(), SpanKind.SERVICE, "OrderService.createOrder", 2, 96, SpanStatus.OK));
        spans.add(span(spans.get(2).spanId(), SpanKind.REPOSITORY, "OrderRepository.save", 3, 10, SpanStatus.OK));
        spans.add(span(spans.get(3).spanId(), SpanKind.DATABASE, "INSERT orders", 4, 2, SpanStatus.OK));
        spans.add(span(spans.get(3).spanId(), SpanKind.DATABASE, "INSERT order_lines", 7, 2, SpanStatus.OK));
        return spans;
    }

    private Span span(String parent, SpanKind kind, String name, long startMs, long durationMs, SpanStatus status) {
        return new Span(TRACE, "%016x".formatted(nextId++), parent, kind, name, "app", startMs * MS, durationMs * MS,
                status, Map.of());
    }
}
