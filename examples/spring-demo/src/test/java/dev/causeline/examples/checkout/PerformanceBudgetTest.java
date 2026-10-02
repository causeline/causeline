// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.internal.tracing.CauselineStats;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The server-side performance budget (PRD section 11). Excluded from {@code ./mvnw verify}; run it
 * with {@code ./mvnw -pl examples/spring-demo -Pperf verify}.
 *
 * <p>Two copies of the demo run side by side, one with Causeline off and one with it on, and
 * requests alternate between them so both see the same machine load. The budget is on the
 * difference in p99 latency.
 */
@Tag("perf")
class PerformanceBudgetTest {

    private static final int WARMUP = 1_500;
    private static final int MEASURED = 3_000;

    private static ConfigurableApplicationContext off;
    private static ConfigurableApplicationContext on;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() {
        off = demo("perf-off", false);
        on = demo("perf-on", true);
    }

    @AfterAll
    static void stop() {
        on.close();
        off.close();
    }

    private static ConfigurableApplicationContext demo(String database, boolean causeline) {
        // Command-line arguments, because builder properties are defaults that application.yml overrides.
        return new SpringApplicationBuilder(CheckoutApplication.class)
                .profiles("dev")
                .run("--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1",
                        "--causeline.enabled=" + causeline,
                        "--causeline.access-token=perf",
                        "--logging.level.root=WARN",
                        "--demo.payment.fast-delay-ms=0");
    }

    @Test
    void addedLatencyPerRequestIsWithinOneMillisecondAtP99() throws Exception {
        fastPayments(off);
        fastPayments(on);

        // A light request (controller only) and a full checkout (service, repository, SQL,
        // outbound HTTP call to the fake payment service, request and response bodies).
        Budget light = measure(() -> get(off, "/api/demo/settings"), () -> get(on, "/api/demo/settings"));
        Budget checkout = measure(() -> checkout(off), () -> checkout(on));

        System.out.printf("[perf] light request    %s%n[perf] checkout         %s%n", light, checkout);
        assertThat(light.addedP99Millis()).as("added p99 latency, light request (ms)").isLessThanOrEqualTo(1.0);
        assertThat(checkout.addedP99Millis()).as("added p99 latency, checkout (ms)").isLessThanOrEqualTo(1.0);
    }

    @Test
    void noSpansAreLostAt200RequestsPerSecond() throws Exception {
        fastPayments(on);
        CauselineStats stats = on.getBean(CauselineStats.class);
        TraceStore store = on.getBean(TraceStore.class);
        long droppedBefore = stats.serverSpansDropped.get();

        int seconds = 10;
        int perSecond = 200;
        // The demo's checkout calls its fake payment service on the same Tomcat, so more checkouts in
        // flight than Tomcat has threads would deadlock the demo itself. 50 in flight still carries
        // 200 requests/s as long as a checkout takes under 250 ms.
        Semaphore inFlight = new Semaphore(50);
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < seconds * perSecond; i++) {
            long due = start + i * 1_000_000_000L / perSecond;
            long wait = due - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
            inFlight.acquire();
            sent.add(HTTP.sendAsync(checkoutRequest(on), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> inFlight.release()));
        }
        long ok = sent.stream().map(CompletableFuture::join).filter(r -> r.statusCode() == 201).count();
        double achieved = sent.size() / ((System.nanoTime() - start) / 1e9);
        Thread.sleep(1_000); // let the export queue drain into the store

        System.out.printf("[perf] load: %d checkouts, %.0f/s achieved, %d ok, %d spans dropped, %d traces stored%n",
                sent.size(), achieved, ok, stats.serverSpansDropped.get() - droppedBefore, store.size());
        assertThat(ok).isEqualTo(sent.size());
        assertThat(stats.serverSpansDropped.get() - droppedBefore).as("server spans dropped").isZero();
        // No drops at a lower rate proves nothing about 200/s: report the run as not done, not passed.
        assumeThat(achieved).as("this machine only reached %.0f checkouts/s", achieved).isGreaterThanOrEqualTo(190);
    }

    /** Latency of the same request against both apps, interleaved. */
    private static Budget measure(Call withoutCauseline, Call withCauseline) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            withoutCauseline.run();
            withCauseline.run();
        }
        long[] offNanos = new long[MEASURED];
        long[] onNanos = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            // Alternate which goes first so neither side always gets the warmer caches.
            if (i % 2 == 0) {
                offNanos[i] = time(withoutCauseline);
                onNanos[i] = time(withCauseline);
            } else {
                onNanos[i] = time(withCauseline);
                offNanos[i] = time(withoutCauseline);
            }
        }
        return new Budget(percentile(offNanos, 50), percentile(offNanos, 99), percentile(onNanos, 50),
                percentile(onNanos, 99));
    }

    private static long time(Call call) throws Exception {
        long start = System.nanoTime();
        call.run();
        return System.nanoTime() - start;
    }

    private static double percentile(long[] nanos, int p) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)] / 1e6;
    }

    record Budget(double offP50, double offP99, double onP50, double onP99) {
        double addedP99Millis() {
            return onP99 - offP99;
        }

        @Override
        public String toString() {
            return "off p50 %.2f p99 %.2f ms | on p50 %.2f p99 %.2f ms | added p99 %.2f ms"
                    .formatted(offP50, offP99, onP50, onP99, addedP99Millis());
        }
    }

    @FunctionalInterface
    interface Call {
        void run() throws Exception;
    }

    private static void fastPayments(ConfigurableApplicationContext app) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri(app, "/api/demo/settings"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"slowPayment\":false,\"failPayment\":false,\"flakyStock\":false}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isLessThan(300);
    }

    private static void get(ConfigurableApplicationContext app, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri(app, path)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static void checkout(ConfigurableApplicationContext app) throws Exception {
        HttpResponse<String> response = HTTP.send(checkoutRequest(app), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private static HttpRequest checkoutRequest(ConfigurableApplicationContext app) {
        return HttpRequest.newBuilder(uri(app, "/api/orders"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                .build();
    }

    private static URI uri(ConfigurableApplicationContext app, String path) {
        return URI.create("http://localhost:" + app.getEnvironment().getProperty("local.server.port") + path);
    }
}
