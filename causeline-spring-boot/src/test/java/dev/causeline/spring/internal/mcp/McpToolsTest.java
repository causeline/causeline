// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.autoconfigure.TestProperties;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.replay.ReplayRecord;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplayStore;
import dev.causeline.spring.internal.web.SourceLocator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SC-17: what a coding agent can see and replay over MCP. */
class McpToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORDER_TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String ORDER_SPAN = "00f067aa0ba902b7";
    private static final String LOOKUP_TRACE = "1af7651916cd43dd8448eb211c80319c";
    private static final String LOOKUP_SPAN = "10f067aa0ba902b7";
    private static final String PAYROLL_TRACE = "2af7651916cd43dd8448eb211c80319c";
    private static final String PAYROLL_SPAN = "20f067aa0ba902b7";
    private static final List<String> SECRETS = List.of("SECRET-AUTH", "SECRET-COOKIE", "SECRET-PASSWORD",
            "SECRET-TOKEN", "SECRET-SQL", "PAYROLL-SALARY");

    private final TraceStore store = new TraceStore();
    private final ReplayStore records = new ReplayStore(10);
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final AgentReplays agentReplays = new AgentReplays(10);
    private final AtomicLong toolCalls = new AtomicLong();
    private HttpServer app;

    @BeforeEach
    void seed() throws Exception {
        app = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        app.createContext("/", exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " auth="
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        app.start();

        long start = 1_790_000_000_000_000_000L;
        store.addAll(List.of(
                new Span(ORDER_TRACE, ORDER_SPAN, null, SpanKind.REQUEST, "POST /api/orders", "shop", start,
                        40_000_000, SpanStatus.ERROR, Map.of(
                                "url.path", "/api/orders",
                                "url.query", "token=SECRET-TOKEN&page=2",
                                "http.request.method", "POST",
                                "http.request.header.authorization", "Bearer SECRET-AUTH",
                                "http.request.header.cookie", "SESSION=SECRET-COOKIE",
                                "http.request.body", "{\"item\":\"globe\",\"password\":\"SECRET-PASSWORD\"}",
                                "http.response.status_code", "500")),
                new Span(ORDER_TRACE, "00f067aa0ba902b8", ORDER_SPAN, SpanKind.DATABASE, "SELECT customers", "shop",
                        start + 1_000_000, 30_000_000, SpanStatus.OK, Map.of(
                                "db.query.text", "SELECT * FROM customers WHERE email = 'SECRET-SQL'")),
                new Span(ORDER_TRACE, "00f067aa0ba902b9", ORDER_SPAN, SpanKind.EXCEPTION, "PaymentTimeoutException",
                        "shop", start + 35_000_000, 0, SpanStatus.ERROR, Map.of(
                                "exception.type", "com.shop.PaymentTimeoutException",
                                "code.location", "PaymentClient.java:48")),
                new Span(LOOKUP_TRACE, LOOKUP_SPAN, null, SpanKind.REQUEST, "GET /api/orders/{id}", "shop", start + 1,
                        5_000_000, SpanStatus.OK, Map.of("url.path", "/api/orders/7")),
                new Span(PAYROLL_TRACE, PAYROLL_SPAN, null, SpanKind.REQUEST, "GET /api/payroll/{month}", "shop",
                        start + 2, 5_000_000, SpanStatus.OK, Map.of(
                                "url.path", "/api/payroll/2026-10",
                                "http.response.body", "{\"salary\":\"PAYROLL-SALARY\"}"))));
        records.put(new ReplayRecord(ORDER_TRACE, ORDER_SPAN, "POST", "/api/orders", "page=2",
                Map.of("Authorization", "Bearer SECRET-AUTH", "Content-Type", "application/json"),
                "{\"item\":\"globe\"}".getBytes(StandardCharsets.UTF_8), true, true, false));
        records.put(new ReplayRecord(LOOKUP_TRACE, LOOKUP_SPAN, "GET", "/api/orders/7", null,
                Map.of("Authorization", "Bearer SECRET-AUTH"), null, false, false, false));
        records.put(new ReplayRecord(PAYROLL_TRACE, PAYROLL_SPAN, "GET", "/api/payroll/2026-10", null, Map.of(), null,
                false, false, false));
    }

    @AfterEach
    void stop() {
        app.stop(0);
    }

    @Test
    void aSeededSecretNeverAppearsInAnyToolResult() {
        McpServer server = server(Map.of());
        List<String> results = List.of(
                raw(server, "list_traces", "{}"),
                raw(server, "get_trace", "{\"traceId\":\"" + ORDER_TRACE + "\"}"),
                raw(server, "get_insights", "{\"traceId\":\"" + ORDER_TRACE + "\"}"),
                raw(server, "security_findings", "{}"),
                raw(server, "security_findings", "{\"traceId\":\"" + ORDER_TRACE + "\"}"),
                raw(server, "compare_traces", "{\"baseline\":\"" + LOOKUP_TRACE + "\",\"traceId\":\"" + ORDER_TRACE
                        + "\",\"onlyChanges\":false}"),
                raw(server, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\"}"));

        results.forEach(result -> assertThat(result).doesNotContain(SECRETS));
        // Ordinary data stays useful to the agent.
        String trace = results.get(1);
        assertThat(trace).contains("globe", "PaymentTimeoutException", "PaymentClient.java:48", "SELECT customers");
    }

    @Test
    void getTraceShowsTheTreeSelfTimeAndWhatCanBeReplayed() {
        JsonNode trace = call(server(Map.of()), "get_trace", "{\"traceId\":\"" + ORDER_TRACE + "\"}");

        assertThat(trace.get("status").asString()).isEqualTo("ERROR");
        JsonNode request = trace.get("spans").get(0);
        assertThat(request.get("name").asString()).isEqualTo("POST /api/orders");
        assertThat(request.get("replayable").asBoolean()).isTrue();
        assertThat(request.get("selfMs").asDouble()).isEqualTo(10.0);
        assertThat(trace.get("insights").toString()).contains("PRIMARY_BOTTLENECK", "SELECT customers");

        JsonNode withoutValues = call(server(Map.of()), "get_trace",
                "{\"traceId\":\"" + ORDER_TRACE + "\",\"attributes\":false}");
        assertThat(withoutValues.get("spans").get(0).has("attributes")).isFalse();
    }

    @Test
    void longValuesAreCut() {
        store.add(new Span(LOOKUP_TRACE, "10f067aa0ba902b8", LOOKUP_SPAN, SpanKind.LOG, "log", "shop",
                1_790_000_000_000_000_002L, 0, SpanStatus.OK, Map.of("log.message", "x".repeat(10_000))));

        String result = raw(server(Map.of()), "get_trace", "{\"traceId\":\"" + LOOKUP_TRACE + "\"}");

        assertThat(result).contains("more characters]").doesNotContain("x".repeat(McpTools.MAX_VALUE_CHARS + 1));
    }

    @Test
    void hiddenRequestsAreInvisibleToEveryTool() {
        McpServer server = server(Map.of("mcp.hide[0]", "/api/payroll/**", "mcp.replay.allow[0]", "* /**"));

        assertThat(call(server, "list_traces", "{}").get("traces")).extracting(t -> t.get("traceId").asString())
                .containsExactlyInAnyOrder(ORDER_TRACE, LOOKUP_TRACE);
        assertThat(error(server, "get_trace", "{\"traceId\":\"" + PAYROLL_TRACE + "\"}"))
                .isEqualTo("No trace " + PAYROLL_TRACE + ". Use list_traces to find one.");
        assertThat(error(server, "replay_request", "{\"traceId\":\"" + PAYROLL_TRACE + "\"}")).contains("No trace");
        assertThat(raw(server, "security_findings", "{}")).doesNotContain(PAYROLL_TRACE);
        assertThat(received).isEmpty();
    }

    @Test
    void getAndHeadReplayByDefaultAndEverythingElseMustBeListed() {
        McpServer server = server(Map.of());

        JsonNode replay = call(server, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\"}");
        assertThat(replay.get("httpStatus").asInt()).isEqualTo(200);
        assertThat(replay.get("replayTraceId").asString()).hasSize(32);
        // The UI labels it, so the developer sees what their agent ran.
        assertThat(agentReplays.contains(replay.get("replayTraceId").asString())).isTrue();
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(received).containsExactly("GET /api/orders/7 auth=Bearer SECRET-AUTH");

        assertThat(error(server, "replay_request", "{\"traceId\":\"" + ORDER_TRACE + "\",\"confirm\":true}"))
                .contains("causeline.mcp.replay.allow", "POST /api/orders");
        assertThat(received).hasSize(1);
    }

    @Test
    void listedUnsafeRequestsStillNeedConfirmation() {
        McpServer server = server(Map.of("mcp.replay.allow[0]", "POST /api/orders"));

        assertThat(error(server, "replay_request", "{\"traceId\":\"" + ORDER_TRACE + "\"}"))
                .contains("confirm: true");
        assertThat(received).isEmpty();

        assertThat(call(server, "replay_request", "{\"traceId\":\"" + ORDER_TRACE + "\",\"confirm\":true}")
                .get("httpStatus").asInt()).isEqualTo(200);
        assertThat(received).containsExactly("POST /api/orders?page=2 auth=Bearer SECRET-AUTH");
    }

    @Test
    void anEditCantTurnAnAllowedRequestIntoABlockedOne() {
        McpServer server = server(Map.of("mcp.replay.allow[0]", "* /api/orders/**",
                "mcp.replay.deny[0]", "* /api/orders/*/refund"));

        assertThat(error(server, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\",\"path\":\"/api/admin/users\"}"))
                .contains("GET /api/admin/users", "causeline.mcp.replay.allow");
        assertThat(error(server, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\",\"path\":\"/api/orders/7/refund\"}"))
                .contains("causeline.mcp.replay.deny");
        assertThat(received).isEmpty();

        call(server, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\",\"path\":\"/api/orders/8\",\"query\":\"full=true\"}");
        assertThat(received).containsExactly("GET /api/orders/8?full=true auth=Bearer SECRET-AUTH");
    }

    @Test
    void theAgentCantChangeHeaders() {
        McpServer server = server(Map.of());

        call(server, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\",\"headers\":{\"Authorization\":\"Bearer admin\"}}");

        assertThat(received).containsExactly("GET /api/orders/7 auth=Bearer SECRET-AUTH");
    }

    @Test
    void sharedTargetsNeedAllowMcp() {
        String qa = "http://localhost:" + app.getAddress().getPort();
        McpServer closed = server(Map.of("replay.targets.qa.base-url", qa));
        assertThat(error(closed, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\",\"target\":\"qa\"}"))
                .contains("causeline.replay.targets.qa.allow-mcp=true");
        assertThat(error(closed, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\",\"target\":\"prod\"}"))
                .contains("Unknown replay target 'prod'");
        assertThat(received).isEmpty();

        McpServer open = server(Map.of("replay.targets.qa.base-url", qa, "replay.targets.qa.allow-mcp", "true"));
        assertThat(call(open, "replay_request", "{\"traceId\":\"" + LOOKUP_TRACE + "\",\"target\":\"qa\"}")
                .get("target").asString()).isEqualTo("qa");
        assertThat(received).hasSize(1);
    }

    @Test
    void replaysAreRateLimited() {
        McpServer server = server(Map.of("mcp.replay.max-per-minute", "2"));
        String args = "{\"traceId\":\"" + LOOKUP_TRACE + "\"}";

        call(server, "replay_request", args);
        call(server, "replay_request", args);
        assertThat(error(server, "replay_request", args)).contains("causeline.mcp.replay.max-per-minute");
        assertThat(received).hasSize(2);

        clock.addAndGet(60_000);
        call(server, "replay_request", args);
        assertThat(received).hasSize(3);
    }

    @Test
    void aTraceWithSeveralRequestsAsksWhichOne() {
        store.add(new Span(ORDER_TRACE, "00f067aa0ba902c0", ORDER_SPAN, SpanKind.REQUEST, "GET /api/x", "shop",
                1_790_000_000_002_000_000L, 1_000_000, SpanStatus.OK, Map.of("url.path", "/api/x")));
        records.put(new ReplayRecord(ORDER_TRACE, "00f067aa0ba902c0", "GET", "/api/x", null, Map.of(), null, false,
                false, false));

        assertThat(error(server(Map.of()), "replay_request", "{\"traceId\":\"" + ORDER_TRACE + "\"}"))
                .contains("pass spanId", ORDER_SPAN, "00f067aa0ba902c0");
    }

    private McpServer server(Map<String, String> overrides) {
        CauselineProperties properties = TestProperties.bind(overrides);
        ReplayService replays = new ReplayService(records, store, properties.replay(), Optional.empty(),
                () -> app.getAddress().getPort());
        McpTools tools = new McpTools(store, id -> Optional.empty(),
                new SpanRedactor(SensitiveData.forExport(List.of())), new McpRules(properties.mcp()), replays,
                new SourceLocator(Path.of(".")), properties.mcp().replay().maxPerMinute(), clock::get, Duration.ZERO,
                agentReplays);
        return new McpServer(tools, JSON, "test", toolCalls::incrementAndGet);
    }

    private static String raw(McpServer server, String tool, String arguments) {
        return server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}");
    }

    private static JsonNode call(McpServer server, String tool, String arguments) {
        JsonNode result = JSON.readTree(raw(server, tool, arguments)).get("result");
        assertThat(result.get("isError").asBoolean()).as(result.toString()).isFalse();
        return result.get("structuredContent");
    }

    private static String error(McpServer server, String tool, String arguments) {
        JsonNode result = JSON.readTree(raw(server, tool, arguments)).get("result");
        assertThat(result.get("isError").asBoolean()).as(result.toString()).isTrue();
        return result.get("content").get(0).get("text").asString();
    }
}
