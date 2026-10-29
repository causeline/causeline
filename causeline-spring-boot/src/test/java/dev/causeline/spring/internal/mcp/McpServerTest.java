// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.TestProperties;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.web.SourceLocator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpServer server = new McpServer(new McpTools(new TraceStore(), id -> Optional.empty(),
            SpanRedactor.NONE, new McpRules(TestProperties.bind(Map.of()).mcp()), null,
            new SourceLocator(Path.of(".")), 20, System::currentTimeMillis, Duration.ZERO, new AgentReplays(10)), JSON, "test", () -> { });

    @Test
    void initializeAgreesOnAVersionAndOffersToolsOnly() {
        JsonNode result = call("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}").get("result");

        assertThat(result.get("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(result.get("capabilities").propertyNames()).containsExactly("tools");
        assertThat(result.get("serverInfo").get("name").asString()).isEqualTo("causeline");
        assertThat(result.get("instructions").asString()).contains("list_traces");

        JsonNode future = call("initialize", "{\"protocolVersion\":\"2099-01-01\"}").get("result");
        assertThat(future.get("protocolVersion").asString()).isEqualTo(McpServer.PROTOCOL_VERSIONS.getFirst());
    }

    @Test
    void listsEveryToolWithASchemaAndOnlyReplayAsNotReadOnly() {
        JsonNode tools = call("tools/list", "{}").get("result").get("tools");

        assertThat(tools).extracting(t -> t.get("name").asString()).containsExactly("list_traces", "get_trace",
                "get_insights", "security_findings", "compare_traces", "source_location", "replay_request");
        tools.forEach(tool -> {
            assertThat(tool.get("inputSchema").get("type").asString()).isEqualTo("object");
            boolean replay = tool.get("name").asString().equals("replay_request");
            assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).isEqualTo(!replay);
        });
    }

    @Test
    void toolRefusalsAreToolErrorsNotProtocolErrors() {
        JsonNode response = call("tools/call", "{\"name\":\"get_trace\",\"arguments\":{\"traceId\":\"0af7651916cd43dd8448eb211c80319c\"}}");

        assertThat(response.has("error")).isFalse();
        assertThat(response.get("result").get("isError").asBoolean()).isTrue();
        assertThat(response.get("result").get("content").get(0).get("text").asString()).contains("No trace");
    }

    @Test
    void successfulCallsReturnTextAndStructuredContent() {
        JsonNode result = call("tools/call", "{\"name\":\"list_traces\",\"arguments\":{}}").get("result");

        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.get("structuredContent").get("traces").isArray()).isTrue();
        assertThat(JSON.readTree(result.get("content").get(0).get("text").asString()))
                .isEqualTo(result.get("structuredContent"));
    }

    @Test
    void notificationsAndMalformedMessagesAreHandled() {
        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")).isNull();
        assertThat(JSON.readTree(server.handle("not json")).get("error").get("code").asInt())
                .isEqualTo(McpServer.PARSE_ERROR);
        assertThat(JSON.readTree(server.handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"))
                .get("error").get("code").asInt()).isEqualTo(McpServer.INVALID_REQUEST);
        assertThat(call("resources/list", "{}").get("error").get("code").asInt()).isEqualTo(McpServer.METHOD_NOT_FOUND);
        assertThat(call("tools/call", "{\"name\":\"rm_rf\"}").get("error").get("code").asInt())
                .isEqualTo(McpServer.INVALID_PARAMS);
        assertThat(call("ping", "{}").get("result").isObject()).isTrue();
    }

    @Test
    void echoesTheRequestId() {
        JsonNode response = JSON.readTree(server.handle("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\",\"method\":\"ping\"}"));
        assertThat(response.get("id").asString()).isEqualTo("abc-1");
    }

    private JsonNode call(String method, String params) {
        return JSON.readTree(server.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":"
                + params + "}"));
    }
}
