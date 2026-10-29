// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.spring.internal.mcp.McpInfo;
import dev.causeline.spring.internal.mcp.McpServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /causeline/mcp} for the developer's coding agent. An annotated controller with a String
 * body and a future result, so Spring MVC and WebFlux both run it unchanged. The access guard
 * checks the token and Origin before a request gets here.
 */
@RestController
public class McpController {

    /**
     * Tools read the whole store, and a replay waits on the network, so they never run on a request
     * thread or the event loop. Calls come one at a time from an agent, so a thread per call is fine.
     */
    private static final Executor TOOLS = Executors.newVirtualThreadPerTaskExecutor();

    private final McpServer server;
    private final McpInfo info;

    public McpController(McpServer server, McpInfo info) {
        this.server = server;
        this.info = info;
    }

    /** For the UI's "Connect your coding agent" panel. Under /causeline/api, so it needs the access token. */
    @GetMapping(CauselinePaths.API_PREFIX + "mcp")
    public McpInfo info() {
        return info;
    }

    @PostMapping(path = CauselinePaths.MCP_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<String>> post(@RequestBody String body) {
        return CompletableFuture.supplyAsync(() -> {
            String response = server.handle(body);
            return response == null ? ResponseEntity.accepted().<String>build() : ResponseEntity.ok(response);
        }, TOOLS);
    }

    /** No server-sent event stream: Causeline never sends a message the agent didn't ask for. */
    @GetMapping(CauselinePaths.MCP_PATH)
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    /** There are no sessions to end. */
    @DeleteMapping(CauselinePaths.MCP_PATH)
    public ResponseEntity<Void> endSession() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }
}
