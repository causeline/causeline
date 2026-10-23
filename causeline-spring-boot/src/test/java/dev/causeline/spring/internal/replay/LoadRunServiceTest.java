// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.TestProperties;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.replay.LoadRunService.Mode;
import dev.causeline.spring.internal.replay.LoadRunService.Settings;
import dev.causeline.spring.internal.replay.LoadRunService.Shape;
import dev.causeline.spring.internal.replay.LoadRunService.State;
import dev.causeline.spring.internal.replay.ReplayService.ReplayException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadRunServiceTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String ORDER = "00f067aa0ba902b7";
    private static final String STOCK = "00f067aa0ba902b8";
    private static final long MS = 1_000_000;

    private final TraceStore traces = new TraceStore();
    private final ReplayStore records = new ReplayStore(100);
    private final List<String> received = new CopyOnWriteArrayList<>();
    /** The fake app's payment client lets one call through at a time when contended. */
    private final Semaphore payment = new Semaphore(1);
    private volatile boolean contended = true;
    private volatile int status = 201;
    private volatile long delayMillis = 15;
    private HttpServer app;

    @BeforeEach
    void start() throws Exception {
        app = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        app.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // Stands in for the instrumented application: each request records its own spans.
        app.createContext("/", exchange -> {
            received.add(exchange.getRequestURI().getPath());
            String[] traceparent = exchange.getRequestHeaders().getFirst("traceparent").split("-");
            long begin = System.nanoTime();
            long waited;
            try {
                long before = System.nanoTime();
                if (contended) {
                    payment.acquire();
                }
                waited = System.nanoTime() - before;
                try {
                    Thread.sleep(delayMillis);
                } finally {
                    if (contended) {
                        payment.release();
                    }
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            long total = System.nanoTime() - begin;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            String request = hex(8);
            traces.add(new Span(traceparent[1], request, traceparent[2], SpanKind.REQUEST,
                    "POST " + exchange.getRequestURI().getPath(), "app", begin, total + MS,
                    status >= 500 ? SpanStatus.ERROR : SpanStatus.OK, Map.of("http.request.body", "{\"card\":\"4111\"}")));
            traces.add(new Span(traceparent[1], hex(8), request, SpanKind.SERVICE, "PaymentClient.charge", "app",
                    begin, total, SpanStatus.OK, Map.of()));
            traces.add(new Span(traceparent[1], hex(8), request, SpanKind.DATABASE, "INSERT orders", "app",
                    begin + waited, 1, SpanStatus.OK, Map.of()));
        });
        app.start();
        // The original trace: a checkout, then a stock lookup 300 ms after it, and a request with nothing captured.
        long t0 = 1_000 * MS;
        traces.add(new Span(TRACE, ORDER, null, SpanKind.REQUEST, "POST /api/orders", "app", t0, 20 * MS, SpanStatus.OK,
                Map.of("http.route", "/api/orders")));
        traces.add(new Span(TRACE, STOCK, null, SpanKind.REQUEST, "GET /api/stock", "app", t0 + 320 * MS, 5 * MS,
                SpanStatus.OK, Map.of("http.route", "/api/stock")));
        traces.add(new Span(TRACE, "00f067aa0ba902b9", null, SpanKind.REQUEST, "GET /api/other", "app", t0 + 400 * MS,
                5 * MS, SpanStatus.OK, Map.of()));
        records.put(new ReplayRecord(TRACE, ORDER, "POST", "/api/orders", null,
                Map.of("Content-Type", "application/json"), "{\"item\":\"book\"}".getBytes(), false, true, false));
        records.put(new ReplayRecord(TRACE, STOCK, "GET", "/api/stock", null, Map.of(), null, false, false, false));
    }

    @AfterEach
    void stop() {
        app.stop(0);
    }

    @Test
    void theContendedSpanGrowsAndOnlyTheSlowestHitsStayInTheStore() {
        LoadRunService runs = service(Map.of());
        LoadRunService.View view = await(runs, runs.start(request(Mode.REQUEST, 30, 10, true)));

        assertThat(view.state()).isEqualTo(State.DONE);
        assertThat(view.sent()).isEqualTo(LoadRunService.BASELINE_HITS + 30);
        assertThat(view.analyzed()).isTrue();
        LoadRunService.StepView step = view.steps().getFirst();
        assertThat(step.route()).isEqualTo("/api/orders");
        assertThat(step.loadStatuses()).containsEntry("201", 30);
        assertThat(step.load().p95()).isGreaterThan(step.baseline().p95());
        assertThat(step.report().rows().getFirst().name()).isEqualTo("PaymentClient.charge");
        assertThat(step.report().rows().getFirst().grew()).isTrue();
        assertThat(step.report().rows()).filteredOn(r -> r.name().equals("INSERT orders"))
                .allMatch(r -> !r.grew());
        // The original and the 5 slowest hits; the other 30 hits were folded and removed.
        assertThat(step.kept()).hasSize(5).allMatch(k -> k.reason() == LoadRunService.KeptReason.SLOWEST);
        assertThat(step.kept()).allMatch(k -> traces.contains(k.traceId()));
        assertThat(traces.size()).isEqualTo(1 + 5);
        assertThat(view.toString()).doesNotContain("4111").doesNotContain("book");
    }

    @Test
    void withoutContentionNothingIsFlaggedAsGrowing() {
        contended = false;
        LoadRunService runs = service(Map.of());
        LoadRunService.View view = await(runs, runs.start(request(Mode.REQUEST, 30, 10, true)));

        assertThat(view.steps().getFirst().report().rows()).filteredOn(r -> r.name().equals("PaymentClient.charge"))
                .allMatch(r -> !r.grew());
    }

    @Test
    void failedHitsAreKeptUpToTheLimit() {
        status = 503;
        contended = false;
        LoadRunService runs = service(Map.of("load.keep-failed", "3"));
        LoadRunService.View view = await(runs, runs.start(request(Mode.REQUEST, 10, 5, true)));

        LoadRunService.StepView step = view.steps().getFirst();
        assertThat(step.loadStatuses()).containsEntry("503", 10);
        assertThat(step.kept()).hasSize(3).allMatch(k -> k.reason() == LoadRunService.KeptReason.FAILED);
        assertThat(traces.size()).isEqualTo(1 + 3);
    }

    @Test
    void aFlowReplaysItsRequestsInOrderWithTheOriginalPauses() {
        contended = false;
        delayMillis = 1;
        LoadRunService runs = service(Map.of());
        LoadRunService.View view = await(runs, runs.start(request(Mode.FLOW, 2, 1, true)));

        assertThat(view.steps()).extracting(LoadRunService.StepView::route).containsExactly("/api/orders", "/api/stock");
        assertThat(view.steps().get(1).thinkMillis()).isEqualTo(300);
        assertThat(view.skipped()).extracting(LoadRunService.Skipped::name).containsExactly("GET /api/other");
        assertThat(received).hasSize(2 * (LoadRunService.BASELINE_HITS + 2));
        for (int i = 0; i < received.size(); i += 2) {
            assertThat(received.subList(i, i + 2)).containsExactly("/api/orders", "/api/stock");
        }
    }

    @Test
    void unsafeRequestsNeedAConfirmationThatNamesTheCount() {
        LoadRunService runs = service(Map.of());
        assertThatThrownBy(() -> runs.start(request(Mode.REQUEST, 100, 10, false)))
                .isInstanceOf(ReplayException.class)
                .hasMessageContaining("POST /api/orders 105 times on local")
                .satisfies(e -> assertThat(((ReplayException) e).status()).isEqualTo(409));
        assertThat(received).isEmpty();
    }

    @Test
    void theLimitsCannotBeExceeded() {
        LoadRunService runs = service(Map.of("load.max-requests", "50", "load.max-concurrency", "4",
                "load.max-duration", "10s"));
        assertRefused(() -> runs.start(request(Mode.REQUEST, 46, 2, true)), 400, "would send 51 requests");
        assertRefused(() -> runs.start(request(Mode.REQUEST, 10, 5, true)), 400, "between 1 and 4");
        assertRefused(() -> runs.start(request(Mode.FLOW, 21, 2, true)), 400, "× 2 requests per flow");
        assertRefused(() -> runs.start(new Settings(TRACE, ORDER, "local", Mode.REQUEST, Shape.RATE, 0, 2, 2, 11,
                false, true, null, null)), 400, "between 1 and 10 seconds");
        assertRefused(() -> runs.start(new Settings(TRACE, ORDER, "local", Mode.REQUEST, Shape.RATE, 0, 2, 2, 5,
                true, true, null, null)), 400, "ramp");
        assertThat(received).isEmpty();
    }

    @Test
    void aTargetMustAllowLoad() {
        Map<String, String> qa = new HashMap<>(Map.of("replay.targets.qa.base-url", base()));
        assertRefused(() -> service(qa).start(onTarget("qa")), 403, "allow-load=true");

        qa.put("replay.targets.qa.allow-load", "true");
        LoadRunService runs = service(qa);
        LoadRunService.View view = await(runs, runs.start(onTarget("qa")));
        // Not this application: only the client's view of each hit.
        assertThat(view.analyzed()).isFalse();
        assertThat(view.steps().getFirst().report()).isNull();
        assertThat(view.steps().getFirst().load().count()).isEqualTo(3);
    }

    @Test
    void oneRunAtATimeAndACancelledRunStopsStartingHits() throws Exception {
        contended = false;
        delayMillis = 50;
        LoadRunService runs = service(Map.of());
        LoadRunService.Run run = runs.start(request(Mode.REQUEST, 200, 1, true));
        assertRefused(() -> runs.start(request(Mode.REQUEST, 10, 1, true)), 409, "already going");

        Thread.sleep(300);
        assertThat(runs.cancel(run.id())).isTrue();
        LoadRunService.View view = await(runs, run);

        assertThat(view.state()).isEqualTo(State.CANCELLED);
        assertThat(view.stopReason()).isEqualTo("Cancelled.");
        assertThat(view.sent()).isLessThan(50);
        assertThat(runs.start(request(Mode.REQUEST, 1, 1, true))).isNotNull();
    }

    @Test
    void aRunPastTheMaximumDurationIsStopped() {
        contended = false;
        delayMillis = 100;
        LoadRunService runs = service(Map.of("load.max-duration", "1s"));
        LoadRunService.View view = await(runs, runs.start(request(Mode.REQUEST, 500, 1, true)));

        assertThat(view.state()).isEqualTo(State.DONE);
        assertThat(view.stopReason()).contains("max-duration");
        assertThat(view.sent()).isLessThan(30);
    }

    @Test
    void aRateRunCountsWhatCouldNotStart() {
        contended = false;
        delayMillis = 300;
        LoadRunService runs = service(Map.of());
        LoadRunService.View view = await(runs, runs.start(new Settings(TRACE, ORDER, "local", Mode.REQUEST, Shape.RATE,
                0, 1, 20, 1, false, true, null, null)));

        assertThat(view.notSent()).isGreaterThan(0);
        assertThat(view.steps().getFirst().load().count() + view.notSent()).isEqualTo(20);
    }

    private static Settings request(Mode mode, int hits, int concurrency, boolean confirm) {
        return new Settings(TRACE, ORDER, "local", mode, Shape.CONCURRENCY, hits, concurrency, 0, 0, false, confirm,
                null, null);
    }

    private static Settings onTarget(String target) {
        return new Settings(TRACE, ORDER, target, Mode.REQUEST, Shape.CONCURRENCY, 3, 1, 0, 0, false, true, null, null);
    }

    private LoadRunService service(Map<String, String> overrides) {
        CauselineProperties properties = TestProperties.bind(overrides);
        ReplayService replays = new ReplayService(records, traces, properties.replay(), Optional.empty(),
                () -> app.getAddress().getPort());
        return new LoadRunService(replays, traces, properties.load());
    }

    private String base() {
        return "http://localhost:" + app.getAddress().getPort();
    }

    /** Waits until the run has finished and every hit's trace was folded. */
    private static LoadRunService.View await(LoadRunService runs, LoadRunService.Run run) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            LoadRunService.View view = run.view();
            if (view.state() != State.RUNNING && view.tracesPending() == 0 && runs.recent().stream()
                    .noneMatch(r -> r.state() == State.RUNNING)) {
                return view;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("The load run did not finish: " + run.view());
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status,
            String message) {
        assertThatThrownBy(call).isInstanceOf(ReplayException.class).hasMessageContaining(message)
                .satisfies(e -> assertThat(((ReplayException) e).status()).isEqualTo(status));
    }

    private static String hex(int bytes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            out.append(String.format("%02x", ThreadLocalRandom.current().nextInt(1, 256)));
        }
        return out.toString();
    }
}
