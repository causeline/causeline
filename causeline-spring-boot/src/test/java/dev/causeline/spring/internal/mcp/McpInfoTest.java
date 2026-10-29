// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.autoconfigure.TestProperties;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplayStore;
import dev.causeline.spring.internal.web.SourceLocator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpInfoTest {

    @Test
    void describesWhatTheAgentMayDoWithoutTargetUrlsOrCredentials() {
        CauselineProperties properties = TestProperties.bind(Map.of(
                "mcp.hide[0]", "/api/admin/**",
                "mcp.replay.allow[0]", "POST /api/orders",
                "mcp.replay.deny[0]", "DELETE /**",
                "replay.targets.qa.base-url", "https://qa.example.internal",
                "replay.targets.qa.auth.type", "bearer",
                "replay.targets.qa.auth.token", "QA-SECRET",
                "replay.targets.staging.base-url", "https://staging.example.internal",
                "replay.targets.staging.allow-mcp", "true"));
        TraceStore store = new TraceStore();
        ReplayService replays = new ReplayService(new ReplayStore(10), store, properties.replay(), Optional.empty(),
                () -> 8080);
        McpTools tools = new McpTools(store, id -> Optional.empty(), SpanRedactor.NONE, new McpRules(properties.mcp()),
                replays, new SourceLocator(Path.of(".")), 20, System::currentTimeMillis, Duration.ZERO,
                new AgentReplays(10));

        McpInfo info = McpInfo.of("/causeline/mcp", properties.mcp(), replays, tools);

        assertThat(info.hide()).containsExactly("/api/admin/**");
        assertThat(info.allow()).containsExactly("POST /api/orders");
        assertThat(info.deny()).containsExactly("DELETE /**");
        assertThat(info.safeMethodsOnly()).isFalse();
        assertThat(info.targets()).containsExactly(new McpInfo.TargetAccess("local", true),
                new McpInfo.TargetAccess("qa", false), new McpInfo.TargetAccess("staging", true));
        assertThat(info.tools()).extracting(McpInfo.ToolInfo::name).contains("list_traces", "replay_request");
        String json = JsonMapper.builder().build().writeValueAsString(info);
        assertThat(json).doesNotContain("QA-SECRET", "qa.example.internal");
    }

    @Test
    void withoutAnAllowListItSaysGetAndHeadOnly() {
        CauselineProperties properties = TestProperties.bind(Map.of());
        McpTools tools = new McpTools(new TraceStore(), id -> Optional.empty(), SpanRedactor.NONE,
                new McpRules(properties.mcp()), null, new SourceLocator(Path.of(".")), 20, System::currentTimeMillis,
                Duration.ZERO, new AgentReplays(10));

        McpInfo info = McpInfo.of("/causeline/mcp", properties.mcp(), null, tools);

        assertThat(info.safeMethodsOnly()).isTrue();
        assertThat(info.replayAvailable()).isFalse();
        assertThat(info.maxReplaysPerMinute()).isEqualTo(20);
    }
}
