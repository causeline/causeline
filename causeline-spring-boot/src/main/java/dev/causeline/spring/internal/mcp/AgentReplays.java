// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The trace IDs of replays a coding agent sent over MCP, so the UI can label them and the
 * developer sees what their agent ran. Bounded like the trace store; the oldest are forgotten.
 */
public final class AgentReplays {

    private final int max;
    private final Set<String> traceIds = new LinkedHashSet<>();

    public AgentReplays(int max) {
        this.max = max;
    }

    public synchronized void add(String traceId) {
        traceIds.add(traceId);
        if (traceIds.size() > max) {
            traceIds.remove(traceIds.iterator().next());
        }
    }

    public synchronized boolean contains(String traceId) {
        return traceIds.contains(traceId);
    }
}
