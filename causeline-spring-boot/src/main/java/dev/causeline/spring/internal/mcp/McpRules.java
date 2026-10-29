// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

/**
 * What the developer lets their coding agent see and replay ({@code causeline.mcp.*}). Only the
 * developer's configuration decides; nothing the agent sends can widen it.
 */
public final class McpRules {

    /** What the agent may replay when the developer listed nothing: methods without side effects. */
    static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD");

    private record Entry(String method, PathPattern pattern, String text) {

        boolean matches(String requestMethod, PathContainer path) {
            return (method == null || method.equals(requestMethod)) && pattern.matches(path);
        }
    }

    private final List<PathPattern> hide;
    private final List<Entry> allow;
    private final List<Entry> deny;

    /** @throws IllegalArgumentException naming the setting when a pattern can't be parsed, so startup fails clearly */
    public McpRules(CauselineProperties.Mcp config) {
        this.hide = config.hide().stream().map(p -> path(p, "causeline.mcp.hide")).toList();
        this.allow = config.replay().allow().stream().map(e -> entry(e, "causeline.mcp.replay.allow")).toList();
        this.deny = config.replay().deny().stream().map(e -> entry(e, "causeline.mcp.replay.deny")).toList();
    }

    /** Whether no MCP tool may show or replay a request to this path. */
    public boolean isHidden(String path) {
        if (path == null) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(path);
        return hide.stream().anyMatch(p -> p.matches(container));
    }

    /**
     * Null when the agent may replay {@code method path}; otherwise why not, naming the setting that
     * would allow it. Called with the request as it will be sent, after the agent's edits.
     */
    public String replayRefusal(String method, String path) {
        String upper = method.toUpperCase(Locale.ROOT);
        PathContainer container = PathContainer.parsePath(path);
        if (isHidden(path)) {
            return path + " is hidden from agents by causeline.mcp.hide.";
        }
        for (Entry entry : deny) {
            if (entry.matches(upper, container)) {
                return upper + " " + path + " is blocked by causeline.mcp.replay.deny (\"" + entry.text() + "\").";
            }
        }
        if (allow.isEmpty()) {
            return SAFE_METHODS.contains(upper) ? null
                    : upper + " " + path + " is not allowed: with no causeline.mcp.replay.allow list, agents may only "
                            + "replay GET and HEAD. The developer can add \"" + upper + " " + path
                            + "\" to causeline.mcp.replay.allow.";
        }
        if (allow.stream().anyMatch(entry -> entry.matches(upper, container))) {
            return null;
        }
        return upper + " " + path + " is not in causeline.mcp.replay.allow. The developer can add \"" + upper + " "
                + path + "\" there.";
    }

    private static Entry entry(String text, String setting) {
        String trimmed = text.strip();
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            throw new IllegalArgumentException(setting + ": \"" + text
                    + "\" must be a method and a path pattern, e.g. \"POST /api/checkout\" or \"* /api/admin/**\".");
        }
        String method = trimmed.substring(0, space).toUpperCase(Locale.ROOT);
        return new Entry(method.equals("*") ? null : method, path(trimmed.substring(space + 1).strip(), setting), trimmed);
    }

    private static PathPattern path(String pattern, String setting) {
        if (!pattern.startsWith("/")) {
            throw new IllegalArgumentException(setting + ": \"" + pattern + "\" must start with /.");
        }
        try {
            return PathPatternParser.defaultInstance.parse(pattern);
        } catch (PatternParseException e) {
            throw new IllegalArgumentException(setting + ": \"" + pattern + "\" is not a valid path pattern: "
                    + e.getMessage(), e);
        }
    }
}
