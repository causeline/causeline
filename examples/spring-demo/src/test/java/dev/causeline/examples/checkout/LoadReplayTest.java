// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Load replay (PRD section 8, SC-15) through the API, against the demo's checkout. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"demo.payment.fast-delay-ms=20", "causeline.access-token=" + LoadReplayTest.TOKEN,
                "causeline.replay.targets.qa.base-url=http://localhost:9"})
@ActiveProfiles("dev")
class LoadReplayTest {

    static final String TOKEN = "load-test-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Autowired
    private DemoSettings settings;

    @AfterEach
    void reset() {
        settings.update(new DemoSettings.Snapshot(true, false, false));
    }

    @Test
    void aContendedPaymentClientIsWhatGrowsUnderLoad() throws Exception {
        settings.update(new DemoSettings.Snapshot(false, false, false, true));
        String traceId = "7cf92f3577b34da6a3ce929d0e0e4736";
        String spanId = checkout(traceId);
        Set<String> before = traceIds();

        JsonNode run = awaitRun(start(traceId, spanId, 40, 20));

        JsonNode step = run.get("steps").get(0);
        assertThat(run.get("state").asString()).isEqualTo("DONE");
        assertThat(run.get("sent").asInt()).isEqualTo(5 + 40);
        assertThat(step.get("loadStatuses").get("201").asInt()).isEqualTo(40);
        JsonNode top = step.get("report").get("rows").get(0);
        assertThat(top.get("name").asString()).isEqualTo("PaymentClient.charge");
        assertThat(top.get("grew").asBoolean()).isTrue();
        // The time is spent waiting in the client, not at the provider.
        assertThat(rowsNamed(step, "POST localhost/fake-payment/charge")).allMatch(r -> !r.get("grew").asBoolean());

        // Only the slowest hits are still in the store; the other 40 were folded into the figures.
        List<String> kept = new ArrayList<>();
        step.get("kept").forEach(k -> kept.add(k.get("traceId").asString()));
        assertThat(kept).hasSize(5).allMatch(store::contains);
        awaitNewTraces(before, Set.copyOf(kept));
        // Results name spans and count statuses; captured values stay out.
        assertThat(run.toString()).doesNotContain("book");
    }

    /**
     * The switch is what makes the payment client grow: without it, its median self time under
     * load is a fraction of the contended one. Medians, not p95: the load comes from this JVM, and
     * on a small machine a GC pause or CPU stall can stretch any span's tail, the switch or not.
     */
    @Test
    void withoutContentionThePaymentClientStopsWaiting() throws Exception {
        String traceId = "8cf92f3577b34da6a3ce929d0e0e4736";
        String spanId = checkout(traceId);

        settings.update(new DemoSettings.Snapshot(false, false, false, true));
        long contended = paymentClientMedian(awaitRun(start(traceId, spanId, 40, 20)));
        settings.update(new DemoSettings.Snapshot(false, false, false, false));
        long free = paymentClientMedian(awaitRun(start(traceId, spanId, 40, 20)));

        assertThat(free).as("median self time of PaymentClient.charge: %d ns without contention, %d ns with", free,
                contended).isLessThan(contended / 4);
    }

    private static long paymentClientMedian(JsonNode run) {
        List<JsonNode> rows = rowsNamed(run.get("steps").get(0), "PaymentClient.charge");
        assertThat(rows).hasSize(1);
        return rows.getFirst().get("loadP50").asLong();
    }

    @Test
    void theApiEnforcesTheConfirmationTheLimitsAndAllowLoad() throws Exception {
        String traceId = "9cf92f3577b34da6a3ce929d0e0e4736";
        String spanId = checkout(traceId);
        Set<String> before = traceIds();

        HttpResponse<String> unconfirmed = api("POST", "/load", JSON.writeValueAsString(Map.of(
                "traceId", traceId, "spanId", spanId, "target", "local", "hits", 100, "concurrency", 10)));
        assertThat(unconfirmed.statusCode()).isEqualTo(409);
        assertThat(unconfirmed.body()).contains("POST /api/orders 105 times on local");

        HttpResponse<String> tooMany = api("POST", "/load", body(traceId, spanId, 996, 10, "local"));
        assertThat(tooMany.statusCode()).isEqualTo(400);
        assertThat(tooMany.body()).contains("max-requests is 1000");

        HttpResponse<String> tooWide = api("POST", "/load", body(traceId, spanId, 10, 21, "local"));
        assertThat(tooWide.statusCode()).isEqualTo(400);

        HttpResponse<String> shared = api("POST", "/load", body(traceId, spanId, 10, 2, "qa"));
        assertThat(shared.statusCode()).isEqualTo(403);
        assertThat(shared.body()).contains("allow-load=true");

        assertThat(traceIds()).isSubsetOf(before);
        JsonNode limits = JSON.readTree(api("GET", "/load/limits", null).body());
        assertThat(limits.get("maxConcurrency").asInt()).isEqualTo(20);
    }

    private String start(String traceId, String spanId, int hits, int concurrency) throws Exception {
        HttpResponse<String> started = api("POST", "/load", body(traceId, spanId, hits, concurrency, "local"));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        return JSON.readTree(started.body()).get("id").asString();
    }

    private static String body(String traceId, String spanId, int hits, int concurrency, String target) {
        Map<String, Object> request = new HashMap<>(Map.of("traceId", traceId, "spanId", spanId, "target", target,
                "hits", hits, "concurrency", concurrency, "confirm", true));
        return JSON.writeValueAsString(request);
    }

    private JsonNode awaitRun(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonNode view = null;
        while (System.nanoTime() < deadline) {
            view = JSON.readTree(api("GET", "/load/" + id, null).body());
            if (!view.get("state").asString().equals("RUNNING") && view.get("tracesPending").asInt() == 0) {
                return view;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("The load run did not finish: " + view);
    }

    private void awaitNewTraces(Set<String> before, Set<String> expected) throws Exception {
        // Async work (the confirmation mail) can end after a hit was folded; the late sweep removes it.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Set<String> added = Set.of();
        while (System.nanoTime() < deadline) {
            added = new HashSet<>(traceIds());
            added.removeAll(before);
            if (added.equals(expected)) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(added).isEqualTo(expected);
    }

    private Set<String> traceIds() {
        Set<String> ids = new HashSet<>();
        store.snapshot().forEach(spans -> ids.add(spans.getFirst().traceId()));
        return ids;
    }

    private static List<JsonNode> rowsNamed(JsonNode step, String name) {
        List<JsonNode> rows = new ArrayList<>();
        step.get("report").get("rows").forEach(r -> {
            if (r.get("name").asString().equals(name)) {
                rows.add(r);
            }
        });
        return rows;
    }

    private String checkout(String traceId) throws Exception {
        HttpRequest original = HttpRequest.newBuilder(uri("/api/orders"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                .build();
        assertThat(send(original).statusCode()).isEqualTo(201);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode replayable = JSON.readTree(api("GET", "/traces/" + traceId + "/replayable", null).body());
            boolean complete = store.get(traceId).orElse(List.of()).stream()
                    .anyMatch(s -> s.kind() == SpanKind.SERVICE && s.name().equals("ConfirmationMailer.sendConfirmation"));
            if (replayable.size() > 0 && complete) {
                return replayable.get(0).get("spanId").asString();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No replayable request in trace " + traceId + ": "
                + store.get(traceId).orElse(List.of()).stream().map(Span::name).toList());
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

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
