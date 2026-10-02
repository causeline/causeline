// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.TraceStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A call that crosses services shows as one trace: the downstream service sends its spans to the
 * upstream service's Causeline (causeline.export.upstream). Both point at each other here, to
 * prove forwarded spans are never sent back.
 */
class ServicesTest {

    private static final String TRACE_ID = "5e1f92f3577b34da6a3ce929d0e0e473";
    private static ConfigurableApplicationContext upstream;
    private static ConfigurableApplicationContext downstream;

    @BeforeAll
    static void start() throws IOException {
        int upstreamPort = freePort();
        int downstreamPort = freePort();
        upstream = app("checkout-demo", upstreamPort, "upstream-token", downstreamPort, "downstream-token");
        downstream = app("inventory-service", downstreamPort, "downstream-token", upstreamPort, "upstream-token");
    }

    @AfterAll
    static void stop() {
        downstream.close();
        upstream.close();
    }

    private static ConfigurableApplicationContext app(String name, int port, String token, int peerPort, String peerToken) {
        return new SpringApplicationBuilder(CheckoutApplication.class).profiles("dev").run(
                "--server.port=" + port,
                "--spring.application.name=" + name,
                "--spring.datasource.url=jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
                "--causeline.access-token=" + token,
                "--causeline.export.upstream.url=http://localhost:" + peerPort,
                "--causeline.export.upstream.token=" + peerToken,
                "--demo.payment.fast-delay-ms=0",
                "--logging.level.root=WARN");
    }

    @Test
    void downstreamSpansArriveInTheUpstreamTraceRedactedAndOnlyOnce() throws Exception {
        // The upstream service calls the downstream one, passing its trace context along.
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port(downstream) + "/api/orders"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer service-secret")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);

        TraceStore upstreamStore = upstream.getBean(TraceStore.class);
        List<Span> received = awaitSpans(upstreamStore, "inventory-service");

        assertThat(received).extracting(Span::name).contains("POST /api/orders", "OrderService.createOrder");
        assertThat(received).allSatisfy(s -> assertThat(s.attributes()).containsEntry("causeline.forwarded", "true"));
        Span request = received.stream().filter(s -> s.name().equals("POST /api/orders")).findFirst().orElseThrow();
        assertThat(request.attributes()).containsEntry("http.request.header.authorization", "[REDACTED]");

        // The downstream app still has its own copy, and nothing came back from upstream.
        Thread.sleep(1_500);
        List<Span> local = downstream.getBean(TraceStore.class).get(TRACE_ID).orElse(List.of());
        assertThat(local).isNotEmpty().noneMatch(s -> s.attributes().containsKey("causeline.forwarded"));
        assertThat(local.stream().filter(s -> s.name().equals("POST /api/orders")).findFirst().orElseThrow()
                .attributes()).containsEntry("http.request.header.authorization", "Bearer service-secret");

        String status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port(downstream) + "/causeline/api/status"))
                .header("X-Causeline-Token", "downstream-token").build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(status).contains("\"upstream\":{\"enabled\":true,\"host\":\"localhost:" + port(upstream) + "\"")
                .containsPattern("\"sent\":[1-9]");
    }

    @Test
    void peerSpansNeedTheAccessToken() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port(upstream) + "/causeline/api/peer-spans"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[]"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static List<Span> awaitSpans(TraceStore store, String source) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<Span> found = List.of();
        while (System.nanoTime() < deadline) {
            found = store.get(TRACE_ID).orElse(List.of()).stream().filter(s -> s.source().equals(source)).toList();
            if (found.stream().anyMatch(s -> s.name().equals("OrderService.createOrder"))) {
                return found;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Downstream spans did not reach the upstream Causeline: " + found);
    }

    private static int port(ConfigurableApplicationContext app) {
        return Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
