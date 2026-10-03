// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Milestone 0.3: replay (SC-08) and comparison (SC-09), end to end. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"demo.payment.delay-ms=30", "causeline.access-token=" + ReplayTest.TOKEN})
@ActiveProfiles("dev")
class ReplayTest {

    static final String TOKEN = "replay-test-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Stands in for a QA environment without Causeline; remembers the last request it received. */
    private static final AtomicReference<Received> QA_RECEIVED = new AtomicReference<>();
    private static final HttpServer QA = startQa();

    record Received(String method, String path, Map<String, List<String>> headers, String body) {
    }

    @DynamicPropertySource
    static void qaTarget(DynamicPropertyRegistry registry) {
        registry.add("causeline.replay.targets.qa.base-url", () -> "http://localhost:" + QA.getAddress().getPort());
        registry.add("causeline.replay.targets.qa.auth.type", () -> "bearer");
        registry.add("causeline.replay.targets.qa.auth.token", () -> "qa-replay-token");
    }

    @AfterAll
    static void stopQa() {
        QA.stop(0);
    }

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Autowired
    private DemoSettings settings;

    @Test
    void replayToQaResendsTheRequestWithTheTargetsAuthorization() throws Exception {
        String traceId = "1cf92f3577b34da6a3ce929d0e0e4736";
        HttpRequest original = HttpRequest.newBuilder(uri("/api/orders"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ORIGINAL-USER-TOKEN")
                .header("Cookie", "SESSION=ORIGINAL-SESSION")
                .header("Idempotency-Key", "original-key")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1,\"password\":\"hunter2\"}"))
                .build();
        assertThat(send(original).statusCode()).isEqualTo(201);
        String spanId = awaitReplayableSpan(traceId);

        // Unsafe method: refused until the side effects are confirmed.
        HttpResponse<String> unconfirmed = api("POST", "/replays", replayRequest(traceId, spanId, "qa", false));
        assertThat(unconfirmed.statusCode()).isEqualTo(409);
        assertThat(QA_RECEIVED.get()).isNull();

        HttpResponse<String> confirmed = api("POST", "/replays", replayRequest(traceId, spanId, "qa", true));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        JsonNode outcome = JSON.readTree(confirmed.body());
        assertThat(outcome.get("httpStatus").asInt()).isEqualTo(201);
        assertThat(outcome.get("sentRedactedFields").asBoolean()).isFalse();

        // SC-08 (revised): the request is resent as it was, except that QA's auth profile replaces
        // the original Authorization, and trace and idempotency headers are fresh.
        Received qa = QA_RECEIVED.get();
        assertThat(qa.method()).isEqualTo("POST");
        assertThat(qa.path()).isEqualTo("/api/orders");
        assertThat(qa.headers().get("Authorization")).containsExactly("Bearer qa-replay-token");
        assertThat(header(qa, "Cookie")).isEqualTo("SESSION=ORIGINAL-SESSION");
        assertThat(header(qa, "X-Causeline-Replay")).isEqualTo(traceId);
        assertThat(header(qa, "traceparent")).contains(outcome.get("replayTraceId").asString()).doesNotContain(traceId);
        assertThat(header(qa, "Idempotency-Key")).isNotBlank().isNotEqualTo("original-key");
        assertThat(qa.body()).isEqualTo("{\"item\":\"book\",\"quantity\":1,\"password\":\"hunter2\"}");
        assertThat(qa.headers().toString()).doesNotContain("ORIGINAL-USER-TOKEN");

        // QA has no Causeline token configured: the comparison says so instead of guessing.
        HttpResponse<String> comparison = api("GET", "/replays/compare?traceId=" + traceId + "&spanId=" + spanId
                + "&replayTraceId=" + outcome.get("replayTraceId").asString() + "&target=qa", null);
        assertThat(JSON.readTree(comparison.body()).get("instrumented").asBoolean()).isFalse();
    }

    @Test
    void replayingAFixedFailureLocallyShowsWhatChanged() throws Exception {
        String traceId = "2cf92f3577b34da6a3ce929d0e0e4736";
        settings.update(new DemoSettings.Snapshot(true, true, false));
        String spanId;
        try {
            HttpRequest failing = HttpRequest.newBuilder(uri("/api/orders"))
                    .header("Content-Type", "application/json")
                    .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                    .build();
            assertThat(send(failing).statusCode()).isEqualTo(500);
            spanId = awaitReplayableSpan(traceId);
        } finally {
            settings.update(new DemoSettings.Snapshot(true, false, false)); // "the fix"
        }

        HttpResponse<String> replayed = api("POST", "/replays", replayRequest(traceId, spanId, "local", true));
        JsonNode outcome = JSON.readTree(replayed.body());
        assertThat(outcome.get("httpStatus").asInt()).isEqualTo(201);
        assertThat(outcome.get("sentRedactedFields").asBoolean()).isFalse(); // nothing sensitive in this body
        assertThat(store.isPinned(traceId)).isTrue();

        // SC-09: span-by-span comparison of the original and the replay.
        JsonNode result = awaitComparison(traceId, spanId, outcome.get("replayTraceId").asString()).get("result");
        assertThat(result.get("original").get("httpStatus").asString()).isEqualTo("500");
        assertThat(result.get("replay").get("httpStatus").asString()).isEqualTo("201");
        assertThat(result.get("rows").get(0).get("change").asString()).isEqualTo("STATUS_CHANGED");
        assertThat(result.get("rows").toString())
                .contains("\"name\":\"PaymentTimeoutException\"", "ONLY_IN_ORIGINAL")
                .contains("\"name\":\"UPDATE orders\"", "ONLY_IN_REPLAY");

        // The replay is labelled in the trace list, pointing back to the original.
        JsonNode list = JSON.readTree(api("GET", "/traces", null).body());
        String replayTraceId = outcome.get("replayTraceId").asString();
        JsonNode replayItem = null;
        for (JsonNode item : list) {
            if (item.get("traceId").asString().equals(replayTraceId)) {
                replayItem = item;
            }
        }
        assertThat(replayItem).isNotNull();
        assertThat(replayItem.get("replayOf").asString()).isEqualTo(traceId);
    }

    @Test
    void traceJsonFromAnotherCauselineReadsBackForRemoteComparison() throws Exception {
        String traceId = "3cf92f3577b34da6a3ce929d0e0e4736";
        send(HttpRequest.newBuilder(uri("/api/orders"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                .build());
        awaitReplayableSpan(traceId);

        // A remote target's /causeline/api/traces/{id} is read back exactly like this.
        String json = api("GET", "/traces/" + traceId, null).body();
        dev.causeline.core.TraceView view = JSON.readValue(json, dev.causeline.core.TraceView.class);

        assertThat(view.traceId()).isEqualTo(traceId);
        assertThat(view.spans()).extracting(dev.causeline.core.TraceView.Row::name).contains("POST /api/orders");
    }

    @Test
    void pausedReplayRunsTheChosenStepWithTheEditedArguments() throws Exception {
        String traceId = "5cf92f3577b34da6a3ce929d0e0e4736";
        HttpRequest original = HttpRequest.newBuilder(uri("/api/orders"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                .build();
        assertThat(send(original).statusCode()).isEqualTo(201);
        String requestSpan = awaitReplayableSpan(traceId);
        String serviceSpan = store.get(traceId).orElseThrow().stream()
                .filter(s -> s.name().equals("OrderService.createOrder")).findFirst().orElseThrow().spanId();

        HttpResponse<String> started = api("POST", "/replay-sessions", JSON.writeValueAsString(Map.of(
                "traceId", traceId, "spanId", requestSpan, "target", "local", "confirm", true,
                "breakpoints", List.of(serviceSpan))));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        String session = JSON.readTree(started.body()).get("id").asString();

        // The replay stops at the service and shows what it was called with.
        JsonNode paused = awaitSession(session, "PAUSED");
        assertThat(paused.get("paused").get("step").asString()).isEqualTo("OrderService.createOrder");
        assertThat(paused.get("paused").get("arguments").toString())
                .contains("\"name\":\"item\"", "\"json\":\"\\\"book\\\"\"", "\"name\":\"quantity\"");

        // A value that doesn't fit the parameter is refused, and the replay stays paused.
        HttpResponse<String> wrong = api("POST", "/replay-sessions/" + session + "/continue",
                JSON.writeValueAsString(Map.of("arguments", Map.of("quantity", "\"many\""))));
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("Argument 'quantity' is not a valid int");

        HttpResponse<String> resumed = api("POST", "/replay-sessions/" + session + "/continue",
                JSON.writeValueAsString(Map.of("arguments", Map.of("item", "\"lamp\"", "quantity", "3"))));
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);

        JsonNode done = awaitSession(session, "DONE");
        assertThat(done.get("outcome").get("httpStatus").asInt()).isEqualTo(201);
        assertThat(done.get("edited").toString()).contains("OrderService.createOrder #1");

        // The replay's trace shows the method ran with the new values, and says they were edited.
        String replayTraceId = done.get("replayTraceId").asString();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Span> replay = List.of();
        while (System.nanoTime() < deadline && replay.stream().noneMatch(s -> s.name().equals("UPDATE orders"))) {
            Thread.sleep(100);
            replay = store.get(replayTraceId).orElse(List.of());
        }
        Span service = replay.stream().filter(s -> s.name().equals("OrderService.createOrder")).findFirst().orElseThrow();
        assertThat(service.attributes()).containsEntry("causeline.arguments", "{\"item\":\"lamp\",\"quantity\":3}")
                .containsEntry("causeline.replay.edited", "true");
        assertThat(replay).anySatisfy(s -> assertThat(s.attributes().getOrDefault("db.query.text", "")).contains("'lamp'"));
    }

    @Test
    void onlyControllerAndServiceStepsCanBePaused() throws Exception {
        String traceId = "6cf92f3577b34da6a3ce929d0e0e4736";
        assertThat(send(HttpRequest.newBuilder(uri("/api/orders"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                .build()).statusCode()).isEqualTo(201);
        String requestSpan = awaitReplayableSpan(traceId);
        String query = store.get(traceId).orElseThrow().stream()
                .filter(s -> s.kind() == SpanKind.DATABASE).findFirst().orElseThrow().spanId();

        HttpResponse<String> refused = api("POST", "/replay-sessions", JSON.writeValueAsString(Map.of(
                "traceId", traceId, "spanId", requestSpan, "target", "qa", "confirm", true, "breakpoints", List.of())));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("Only a replay to this application");

        HttpResponse<String> notAStep = api("POST", "/replay-sessions", JSON.writeValueAsString(Map.of(
                "traceId", traceId, "spanId", requestSpan, "target", "local", "confirm", true, "breakpoints", List.of(query))));
        assertThat(notAStep.statusCode()).isEqualTo(400);
        assertThat(notAStep.body()).contains("only controller and @Observed service methods");
    }

    private JsonNode awaitSession(String session, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode view = null;
        while (System.nanoTime() < deadline) {
            view = JSON.readTree(api("GET", "/replay-sessions/" + session, null).body());
            if (view.get("state").asString().equals(state)) {
                return view;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Replay session never reached " + state + ": " + view);
    }

    private String awaitReplayableSpan(String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            boolean serverSpanStored = store.get(traceId).orElse(List.of()).stream()
                    .anyMatch(s -> s.kind() == SpanKind.REQUEST && s.name().equals("POST /api/orders"));
            JsonNode replayable = JSON.readTree(api("GET", "/traces/" + traceId + "/replayable", null).body());
            if (serverSpanStored && replayable.size() > 0) {
                assertThat(replayable.get(0).get("route").asString()).isEqualTo("/api/orders");
                assertThat(replayable.get(0).get("unsafe").asBoolean()).isTrue();
                assertThat(replayable.toString()).doesNotContain("book", "hunter2");
                return replayable.get(0).get("spanId").asString();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No replayable request in trace " + traceId + ": "
                + store.get(traceId).orElse(List.of()).stream().map(Span::name).toList());
    }

    private JsonNode awaitComparison(String traceId, String spanId, String replayTraceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        HttpResponse<String> response = null;
        while (System.nanoTime() < deadline) {
            response = api("GET", "/replays/compare?traceId=" + traceId + "&spanId=" + spanId + "&replayTraceId="
                    + replayTraceId + "&target=local", null);
            // The replay's spans arrive shortly after its response; the whole subtree must be in.
            if (response.statusCode() == 200 && response.body().contains("UPDATE orders")) {
                return JSON.readTree(response.body());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No comparison: " + (response == null ? "no response" : response.body()));
    }

    private static String replayRequest(String traceId, String spanId, String target, boolean confirm) {
        return JSON.writeValueAsString(Map.of("traceId", traceId, "spanId", spanId, "target", target, "confirm", confirm));
    }

    private HttpResponse<String> api(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/causeline/api" + path))
                .header("X-Causeline-Token", TOKEN)
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String header(Received received, String name) {
        return received.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().getFirst())
                .findFirst()
                .orElse(null);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static HttpServer startQa() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                QA_RECEIVED.set(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        Map.copyOf(exchange.getRequestHeaders()), body));
                byte[] reply = "{\"orderId\":99,\"status\":\"PAID\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
