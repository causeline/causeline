// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceAssemblerTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String CLICK = "a000000000000001";
    private static final String FETCH = "a000000000000002";
    private static final String SERVER = "b000000000000001";
    private static final String SERVICE = "b000000000000002";

    @Test
    void centresServerSubtreeInsideClientRequest() {
        // Browser clock around 1_000_000; server clock is 5_000_000 ahead.
        Span click = span(CLICK, null, SpanKind.UI_ACTION, "browser", 1_000_000, 1_000);
        Span fetch = span(FETCH, CLICK, SpanKind.REQUEST, "browser", 1_000_100, 900);
        Span server = span(SERVER, FETCH, SpanKind.REQUEST, "checkout", 6_000_000, 800);
        Span service = span(SERVICE, SERVER, SpanKind.SERVICE, "checkout", 6_000_100, 600);

        TraceView view = TraceAssembler.assemble(TRACE, List.of(service, server, fetch, click));

        assertThat(view.spans()).extracting(TraceView.Row::spanId)
                .containsExactly(CLICK, FETCH, SERVER, SERVICE);
        assertThat(view.spans()).extracting(TraceView.Row::depth).containsExactly(0, 1, 2, 3);
        // fetch starts at offset 100 and lasts 900; server (800) is centred: 100 + 50.
        assertThat(row(view, SERVER).offsetNanos()).isEqualTo(150);
        // The service keeps its distance from the server span: same clock, same shift.
        assertThat(row(view, SERVICE).offsetNanos()).isEqualTo(250);
        assertThat(row(view, SERVER).clockSkew()).isFalse();
        assertThat(view.durationNanos()).isEqualTo(1_000);
        assertThat(view.name()).isEqualTo("click");
    }

    @Test
    void flagsServerSpanLongerThanItsClientSpan() {
        Span fetch = span(FETCH, null, SpanKind.REQUEST, "browser", 1_000, 100);
        Span server = span(SERVER, FETCH, SpanKind.REQUEST, "checkout", 99_000, 300);

        TraceView view = TraceAssembler.assemble(TRACE, List.of(fetch, server));

        assertThat(row(view, SERVER).offsetNanos()).isZero();
        assertThat(row(view, SERVER).clockSkew()).isTrue();
    }

    @Test
    void selfTimeUsesChildrenOnTheParentsClock() {
        Span fetch = span(FETCH, null, SpanKind.REQUEST, "browser", 1_000, 900);
        Span server = span(SERVER, FETCH, SpanKind.REQUEST, "checkout", 99_000, 800);

        TraceView view = TraceAssembler.assemble(TRACE, List.of(fetch, server));

        assertThat(row(view, FETCH).selfNanos()).isEqualTo(100);
        assertThat(row(view, SERVER).selfNanos()).isEqualTo(800);
    }

    @Test
    void spanWithMissingParentBecomesARoot() {
        // The backend span arrives before the browser has flushed its spans.
        Span server = span(SERVER, FETCH, SpanKind.REQUEST, "checkout", 5_000, 800);
        Span service = span(SERVICE, SERVER, SpanKind.SERVICE, "checkout", 5_100, 600);

        TraceView view = TraceAssembler.assemble(TRACE, List.of(service, server));

        assertThat(row(view, SERVER).depth()).isZero();
        assertThat(row(view, SERVICE).depth()).isEqualTo(1);
        assertThat(view.name()).isEqualTo("server");
    }

    @Test
    void traceIsErrorWhenAnySpanFailed() {
        Span fetch = span(FETCH, null, SpanKind.REQUEST, "browser", 0, 100);
        Span failed = new Span(TRACE, SERVER, FETCH, SpanKind.REQUEST, "server", "checkout", 0, 50,
                SpanStatus.ERROR, Map.of());

        assertThat(TraceAssembler.assemble(TRACE, List.of(fetch, failed)).status()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    void handledExceptionDoesNotFailTheTrace() {
        Span service = span(SERVER, null, SpanKind.SERVICE, "checkout", 0, 100);
        Span logged = new Span(TRACE, "c000000000000001", SERVER, SpanKind.EXCEPTION, "StockUnavailable", "checkout",
                50, 0, SpanStatus.UNSET, Map.of("exception.type", "x.StockUnavailable", "code.location", "Stock.java:9",
                        TraceAssembler.HANDLED_ATTRIBUTE, "true"));

        TraceView view = TraceAssembler.assemble(TRACE, List.of(service, logged));

        assertThat(view.status()).isEqualTo(SpanStatus.OK);
        assertThat(view.spans()).extracting(TraceView.Row::spanId).contains("c000000000000001");
    }

    @Test
    void loggedThenRethrownExceptionIsShownOnceAsThrown() {
        Map<String, String> where = Map.of("exception.type", "x.PaymentTimeout", "code.location", "Pay.java:38");
        Span service = span(SERVER, null, SpanKind.SERVICE, "checkout", 0, 100);
        Span logged = new Span(TRACE, "c000000000000001", SERVER, SpanKind.EXCEPTION, "PaymentTimeout", "checkout",
                40, 0, SpanStatus.UNSET, withHandled(where));
        Span thrown = new Span(TRACE, "c000000000000002", SERVER, SpanKind.EXCEPTION, "PaymentTimeout", "checkout",
                41, 0, SpanStatus.ERROR, where);

        TraceView view = TraceAssembler.assemble(TRACE, List.of(service, logged, thrown));

        assertThat(view.spans()).filteredOn(r -> r.kind() == SpanKind.EXCEPTION)
                .extracting(TraceView.Row::spanId)
                .containsExactly("c000000000000002");
    }

    private static Map<String, String> withHandled(Map<String, String> attributes) {
        Map<String, String> copy = new java.util.HashMap<>(attributes);
        copy.put(TraceAssembler.HANDLED_ATTRIBUTE, "true");
        return copy;
    }

    private static TraceView.Row row(TraceView view, String spanId) {
        return view.spans().stream().filter(r -> r.spanId().equals(spanId)).findFirst().orElseThrow();
    }

    private static Span span(String id, String parent, SpanKind kind, String source, long start, long duration) {
        String name = switch (id) {
            case CLICK -> "click";
            case FETCH -> "fetch";
            case SERVER -> "server";
            default -> "service";
        };
        return new Span(TRACE, id, parent, kind, name, source, start, duration, SpanStatus.OK, Map.of());
    }
}
