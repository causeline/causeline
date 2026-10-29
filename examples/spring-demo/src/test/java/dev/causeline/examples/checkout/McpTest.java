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
 * SC-17: a coding agent finds the failed checkout, reads where it failed, and replays it, through
 * a real HTTP MCP session the way Claude Code would talk to it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"demo.payment.delay-ms=30", "causeline.access-token=" + McpTest.TOKEN,
                "causeline.mcp.replay.allow[0]=POST /api/orders",
                "causeline.mcp.hide[0]=/api/scenarios/**"})
@ActiveProfiles("dev")
class McpTest {

    static final String TOKEN = "mcp-test-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BROWSER_SPAN_ID = "00f067aa0ba902b7";

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Autowired
    private DemoSettings settings;

    @Test
    void anAgentFindsExplainsAndReplaysTheFailedCheckout() throws Exception {
        String traceId = "e1f92f3577b34da6a3ce929d0e0e4736";
        settings.update(new DemoSettings.Snapshot(true, true, false));
        try {
            HttpRequest checkout = HttpRequest.newBuilder(uri("/api/orders"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer SECRET-AUTH")
                    .header("traceparent", "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"item\":\"book\",\"quantity\":1,\"password\":\"SECRET-PASSWORD\"}"))
                    .build();
            assertThat(HttpClient.newHttpClient().send(checkout, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(500);
            awaitKinds(traceId, List.of(SpanKind.REQUEST, SpanKind.EXCEPTION));
        } finally {
            settings.update(new DemoSettings.Snapshot(true, false, false));
        }

        JsonNode init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test-agent\",\"version\":\"1\"}}");
        assertThat(init.get("result").get("serverInfo").get("name").asString()).isEqualTo("causeline");
        assertThat(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode()).isEqualTo(202);

        JsonNode failed = tool("list_traces", "{\"failedOnly\":true,\"name\":\"/api/orders\"}");
        assertThat(failed.get("traces")).extracting(t -> t.get("traceId").asString()).contains(traceId);

        String trace = tool("get_trace", "{\"traceId\":\"" + traceId + "\"}").toString();
        assertThat(trace).contains("PaymentTimeoutException", "PaymentClient.java:", "\"replayable\":true");
        // An agent's context goes to a model provider: what leaves is redacted like an export.
        assertThat(trace).doesNotContain("SECRET-AUTH", "SECRET-PASSWORD");

        // The developer "fixes" the payment provider, and the agent checks the fix.
        JsonNode replay = tool("replay_request", "{\"traceId\":\"" + traceId + "\",\"confirm\":true}");
        assertThat(replay.get("httpStatus").asInt()).isEqualTo(201);
        JsonNode comparison = replay.get("comparison");
        assertThat(comparison.get("baseline").get("status").asString()).isEqualTo("ERROR");
        assertThat(comparison.get("compared").get("status").asString()).isNotEqualTo("ERROR");
        assertThat(comparison.get("spans").toString()).contains("PaymentTimeoutException", "ONLY_IN_ORIGINAL");

        // The developer sees it in the UI: the replay is labelled as the agent's, and the agent counts as connected.
        String replayTraceId = replay.get("replayTraceId").asString();
        JsonNode summaries = JSON.readTree(uiGet("/causeline/api/traces"));
        JsonNode summary = java.util.stream.StreamSupport.stream(summaries.spliterator(), false)
                .filter(t -> t.get("traceId").asString().equals(replayTraceId)).findFirst().orElseThrow();
        assertThat(summary.get("byAgent").asBoolean()).isTrue();
        assertThat(summary.get("replayOf").asString()).isEqualTo(traceId);
        assertThat(JSON.readTree(uiGet("/causeline/api/status")).get("onboarding").get("agentCalls").asInt())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void theUiShowsWhatTheAgentMayDo() throws Exception {
        JsonNode info = JSON.readTree(uiGet("/causeline/api/mcp"));

        assertThat(info.get("path").asString()).isEqualTo("/causeline/mcp");
        assertThat(info.get("allow").get(0).asString()).isEqualTo("POST /api/orders");
        assertThat(info.get("hide").get(0).asString()).isEqualTo("/api/scenarios/**");
        assertThat(info.get("safeMethodsOnly").asBoolean()).isFalse();
        assertThat(info.get("targets").toString()).contains("\"name\":\"local\",\"allowed\":true");
        assertThat(info.get("tools")).hasSize(7);
    }

    private String uiGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("X-Causeline-Token", TOKEN).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void hiddenAndUnlistedRequestsStayOutOfReach() throws Exception {
        String scenario = "e2f92f3577b34da6a3ce929d0e0e4736";
        HttpRequest hidden = HttpRequest.newBuilder(uri("/api/scenarios/customers/1"))
                .header("traceparent", "00-" + scenario + "-" + BROWSER_SPAN_ID + "-01").GET().build();
        HttpClient.newHttpClient().send(hidden, HttpResponse.BodyHandlers.discarding());
        awaitKinds(scenario, List.of(SpanKind.REQUEST));

        assertThat(tool("list_traces", "{\"limit\":100}").toString()).doesNotContain(scenario);
        assertThat(toolError("get_trace", "{\"traceId\":\"" + scenario + "\"}")).contains("No trace");

        // A listed POST, edited into a path the developer didn't list, is refused.
        String order = "e3f92f3577b34da6a3ce929d0e0e4736";
        HttpRequest checkout = HttpRequest.newBuilder(uri("/api/orders")).header("Content-Type", "application/json")
                .header("traceparent", "00-" + order + "-" + BROWSER_SPAN_ID + "-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\",\"quantity\":1}")).build();
        HttpClient.newHttpClient().send(checkout, HttpResponse.BodyHandlers.discarding());
        awaitKinds(order, List.of(SpanKind.REQUEST, SpanKind.HTTP_CLIENT));
        assertThat(toolError("replay_request", "{\"traceId\":\"" + order + "\",\"confirm\":true,\"path\":\"/api/settings\"}"))
                .contains("causeline.mcp.replay.allow");
    }

    @Test
    void theEndpointNeedsTheToken() throws Exception {
        HttpRequest anonymous = HttpRequest.newBuilder(uri("/causeline/mcp")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).build();
        assertThat(HttpClient.newHttpClient().send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);

        HttpRequest stream = HttpRequest.newBuilder(uri("/causeline/mcp")).header("X-Causeline-Token", TOKEN)
                .header("Accept", "text/event-stream").GET().build();
        assertThat(HttpClient.newHttpClient().send(stream, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(405);
    }

    private JsonNode tool(String name, String arguments) throws Exception {
        JsonNode result = rpc("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}").get("result");
        assertThat(result.get("isError").asBoolean()).as(result.toString()).isFalse();
        return result.get("structuredContent");
    }

    private String toolError(String name, String arguments) throws Exception {
        JsonNode result = rpc("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}").get("result");
        assertThat(result.get("isError").asBoolean()).as(result.toString()).isTrue();
        return result.get("content").get(0).get("text").asString();
    }

    private JsonNode rpc(String method, String params) throws Exception {
        HttpResponse<String> response = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
                + "\",\"params\":" + params + "}");
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    /** As an MCP client sends it: JSON, accepting JSON or an event stream, with the protocol version header. */
    private HttpResponse<String> send(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/causeline/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .header("Authorization", "Bearer " + TOKEN)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void awaitKinds(String traceId, List<SpanKind> kinds) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<Span> spans = store.get(traceId).orElse(List.of());
            if (kinds.stream().allMatch(k -> spans.stream().anyMatch(s -> s.kind() == k))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Trace " + traceId + " never got " + kinds);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
