// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SC-10: Causeline works with no account or external service. Name resolution in this JVM is
 * limited to localhost ({@link LocalOnlyResolverProvider}); the whole flow (capture, UI, export,
 * replay and comparison) must work without anything trying to resolve another host.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"demo.payment.delay-ms=30", "causeline.access-token=" + LocalOnlyTest.TOKEN})
@ActiveProfiles("dev")
class LocalOnlyTest {

    static final String TOKEN = "local-only-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Test
    void theFullFlowWorksWithTheNetworkRestrictedToLocalhost() throws Exception {
        String traceId = "7cf92f3577b34da6a3ce929d0e0e4736";
        HttpResponse<String> checkout = send(HttpRequest.newBuilder(uri("/api/orders"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}"))
                .build());
        assertThat(checkout.statusCode()).isEqualTo(201);

        // UI, trace, export, status.
        assertThat(send(HttpRequest.newBuilder(uri("/causeline/?token=" + TOKEN)).build()).statusCode()).isEqualTo(200);
        String spanId = awaitReplayableSpan(traceId);
        assertThat(api("GET", "/traces/" + traceId, null).statusCode()).isEqualTo(200);
        assertThat(api("GET", "/traces/" + traceId + "/export", null).statusCode()).isEqualTo(200);
        assertThat(api("GET", "/status", null).statusCode()).isEqualTo(200);

        // Replay to this app and compare.
        HttpResponse<String> replay = api("POST", "/replays", JSON.writeValueAsString(java.util.Map.of(
                "traceId", traceId, "spanId", spanId, "target", "local", "confirm", true)));
        assertThat(replay.statusCode()).isEqualTo(200);
        String replayTraceId = JSON.readTree(replay.body()).get("replayTraceId").asString();
        assertThat(awaitComparison(traceId, spanId, replayTraceId)).isNotNull();

        assertThat(LocalOnlyResolverProvider.refusedLookups()).filteredOn(host -> !host.equals(PROBE))
                .as("host names something tried to resolve").isEmpty();
    }

    private static final String PROBE = "sc10-probe.example.com";

    @Test
    void theRestrictionIsActive() {
        // Guards the test above: if the resolver were not installed, it would prove nothing.
        assertThatThrownBy(() -> InetAddress.getByName(PROBE)).isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("restricted to localhost");
    }

    private String awaitReplayableSpan(String traceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            boolean stored = store.get(traceId).orElse(List.of()).stream().anyMatch(s -> s.kind() == SpanKind.REQUEST);
            JsonNode replayable = JSON.readTree(api("GET", "/traces/" + traceId + "/replayable", null).body());
            if (stored && replayable.size() > 0) {
                return replayable.get(0).get("spanId").asString();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No replayable request in trace " + traceId);
    }

    private JsonNode awaitComparison(String traceId, String spanId, String replayTraceId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = api("GET", "/replays/compare?traceId=" + traceId + "&spanId=" + spanId
                    + "&replayTraceId=" + replayTraceId + "&target=local", null);
            if (response.statusCode() == 200 && response.body().contains("UPDATE orders")) {
                return JSON.readTree(response.body());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No comparison for replay " + replayTraceId);
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
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
