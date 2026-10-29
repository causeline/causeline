// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.mcp.AgentReplays;
import dev.causeline.spring.internal.mcp.McpInfo;
import dev.causeline.spring.internal.mcp.McpRules;
import dev.causeline.spring.internal.mcp.McpServer;
import dev.causeline.spring.internal.mcp.McpTools;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplayStore;
import dev.causeline.spring.internal.tracing.CauselineStats;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** {@code /causeline/mcp}, the same for servlet and WebFlux applications (PRD section 8). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@ConditionalOnClass(name = "tools.jackson.databind.json.JsonMapper")
@ConditionalOnProperty(prefix = "causeline.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CauselineMcpConfiguration {

    @Bean
    AgentReplays causelineAgentReplays(CauselineProperties properties) {
        return new AgentReplays(properties.store().maxTraces());
    }

    @Bean
    McpController causelineMcpController(TraceStore store, ObjectProvider<ReplayStore> replayStore,
            ObjectProvider<ReplayService> replayService, SpanRedactor redactor, CauselineProperties properties,
            AgentReplays agentReplays, CauselineStats stats) {
        CauselineProperties.Mcp mcp = properties.mcp();
        ReplayService replays = replayService.getIfAvailable();
        McpTools tools = new McpTools(store, traceId -> {
            ReplayStore records = replayStore.getIfAvailable();
            return records == null ? Optional.empty() : records.replayOf(traceId);
        }, redactor, new McpRules(mcp), replays,
                new SourceLocator(Path.of(System.getProperty("user.dir", "."))), mcp.replay().maxPerMinute(),
                System::currentTimeMillis, Duration.ofSeconds(5), agentReplays);
        String version = Optional.ofNullable(McpController.class.getPackage().getImplementationVersion()).orElse("dev");
        McpServer server = new McpServer(tools, JsonMapper.builder().build(), version, () -> {
            stats.mcpToolCalls.incrementAndGet();
            stats.lastMcpCallAt = System.currentTimeMillis();
        });
        return new McpController(server, McpInfo.of(CauselinePaths.MCP_PATH, mcp, replays, tools));
    }
}
