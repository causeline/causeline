// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.internal.replay.ReplayService;
import java.util.List;

/**
 * What the UI shows about agent access: where to connect and what the agent may do. Read-only and
 * fixed at startup, since only configuration can change it. Target base URLs and credentials are
 * left out.
 *
 * @param path             where MCP clients connect, relative to the application
 * @param safeMethodsOnly  no allow list: the agent may replay only GET and HEAD
 * @param targets          replay targets and whether the agent may replay to each
 * @param replayAvailable  false when the application can't replay at all
 */
public record McpInfo(String path, List<String> hide, List<String> allow, List<String> deny, boolean safeMethodsOnly,
        int maxReplaysPerMinute, List<TargetAccess> targets, boolean replayAvailable, List<ToolInfo> tools) {

    public record TargetAccess(String name, boolean allowed) {
    }

    public record ToolInfo(String name, String description, boolean readOnly) {
    }

    public static McpInfo of(String path, CauselineProperties.Mcp config, ReplayService replays, McpTools tools) {
        List<TargetAccess> targets = replays == null ? List.of()
                : replays.targets().keySet().stream().sorted((a, b) -> a.equals(ReplayService.LOCAL_TARGET) ? -1
                        : b.equals(ReplayService.LOCAL_TARGET) ? 1 : a.compareTo(b))
                        .map(name -> new TargetAccess(name, replays.allowsMcp(name)))
                        .toList();
        return new McpInfo(path, config.hide(), config.replay().allow(), config.replay().deny(),
                config.replay().allow().isEmpty(), config.replay().maxPerMinute(), targets, replays != null,
                tools.tools().stream().map(t -> new ToolInfo(t.name(), t.description(), t.readOnly())).toList());
    }
}
