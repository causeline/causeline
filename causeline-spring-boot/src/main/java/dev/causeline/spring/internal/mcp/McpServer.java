// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The part of the Model Context Protocol Causeline speaks: JSON-RPC over Streamable HTTP with plain
 * JSON responses, and tools only. No sessions, server-sent events or server-initiated messages, so
 * there is nothing to keep between requests. Stack-free: the controller hands it the request body.
 */
public final class McpServer {

    /** Newest first; a client asking for another version gets the newest. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    static final String INSTRUCTIONS = "Causeline records what the running application did for each user action: "
            + "the React click, the HTTP requests, controller, service and repository methods, SQL, outbound calls, "
            + "exceptions with the line they were thrown at, logs, and the React state update, as one trace. Start "
            + "with list_traces (failedOnly or name to narrow it), then get_trace or get_insights. After changing the "
            + "code and restarting, replay_request sends the original request again and compares the runs. Secrets "
            + "in captured values are redacted.";

    private final JsonMapper json;
    private final Map<String, McpTools.Tool> tools = new LinkedHashMap<>();
    private final String version;
    private final Runnable onToolCall;

    /** @param onToolCall counts agent activity for the UI; runs once per {@code tools/call} */
    public McpServer(McpTools tools, JsonMapper json, String version, Runnable onToolCall) {
        this.json = json;
        this.version = version;
        this.onToolCall = onToolCall;
        tools.tools().forEach(tool -> this.tools.put(tool.name(), tool));
    }

    /** The response body, or null when the message needs none (a notification or a response); HTTP 202 then. */
    public String handle(String body) {
        JsonNode message;
        try {
            message = json.readTree(body);
        } catch (JacksonException e) {
            return error(null, PARSE_ERROR, "Not valid JSON.");
        }
        if (message == null || !message.isObject()) {
            return error(null, INVALID_REQUEST, "Send one JSON-RPC message as an object; batches aren't supported.");
        }
        JsonNode id = message.get("id");
        JsonNode method = message.get("method");
        if (message.has("result") || message.has("error")) {
            return null; // a response; Causeline never sends requests, so there is nothing waiting for it
        }
        if (method == null || !method.isString()) {
            return id == null ? null : error(id, INVALID_REQUEST, "Missing method.");
        }
        if (id == null || id.isNull()) {
            return null; // notifications, e.g. notifications/initialized
        }
        JsonNode params = message.path("params");
        try {
            return switch (method.asString()) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, Map.of());
                case "tools/list" -> result(id, Map.of("tools", tools.values().stream().map(McpServer::describe).toList()));
                case "tools/call" -> call(id, params);
                default -> error(id, METHOD_NOT_FOUND, "Unknown method " + method.asString() + ".");
            };
        } catch (RuntimeException e) {
            log.debug("MCP {} failed", method.asString(), e);
            return error(id, INTERNAL_ERROR, "Causeline could not answer: " + e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> initialize(JsonNode params) {
        String requested = params.path("protocolVersion").isString() ? params.path("protocolVersion").asString() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst());
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "causeline", "title", "Causeline", "version", version));
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private String call(JsonNode id, JsonNode params) {
        JsonNode name = params.path("name");
        McpTools.Tool tool = name.isString() ? tools.get(name.asString()) : null;
        if (tool == null) {
            return error(id, INVALID_PARAMS, "Unknown tool " + (name.isString() ? name.asString() : "") + ".");
        }
        onToolCall.run();
        JsonNode arguments = params.path("arguments");
        if (!arguments.isObject()) {
            arguments = json.createObjectNode();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object value = tool.call().apply(arguments);
            result.put("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(value))));
            result.put("structuredContent", value);
            result.put("isError", false);
        } catch (McpTools.ToolError e) {
            result.put("content", List.of(Map.of("type", "text", "text", e.getMessage())));
            result.put("isError", true);
        }
        return result(id, result);
    }

    private static Map<String, Object> describe(McpTools.Tool tool) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", tool.name());
        out.put("description", tool.description());
        out.put("inputSchema", tool.inputSchema());
        out.put("annotations", Map.of(
                "readOnlyHint", tool.readOnly(),
                "destructiveHint", !tool.readOnly(),
                "idempotentHint", tool.readOnly(),
                "openWorldHint", false));
        return out;
    }

    private String result(JsonNode id, Object result) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", json.valueToTree(result));
        return json.writeValueAsString(response);
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return json.writeValueAsString(response);
    }
}
