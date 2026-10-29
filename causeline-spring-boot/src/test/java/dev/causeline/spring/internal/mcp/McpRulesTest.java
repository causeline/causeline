// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.causeline.spring.autoconfigure.TestProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpRulesTest {

    @Test
    void withoutAnAllowListOnlyGetAndHeadMayBeReplayed() {
        McpRules rules = rules(Map.of());

        assertThat(rules.replayRefusal("GET", "/api/orders/7")).isNull();
        assertThat(rules.replayRefusal("head", "/api/orders/7")).isNull();
        assertThat(rules.replayRefusal("POST", "/api/orders"))
                .contains("only replay GET and HEAD", "\"POST /api/orders\"", "causeline.mcp.replay.allow");
        assertThat(rules.replayRefusal("DELETE", "/api/orders/7")).isNotNull();
    }

    @Test
    void anAllowListReplacesTheDefault() {
        McpRules rules = rules(Map.of(
                "mcp.replay.allow[0]", "POST /api/orders",
                "mcp.replay.allow[1]", "* /api/cart/{id}",
                "mcp.replay.allow[2]", "GET /api/**"));

        assertThat(rules.replayRefusal("POST", "/api/orders")).isNull();
        assertThat(rules.replayRefusal("PUT", "/api/cart/42")).isNull();
        assertThat(rules.replayRefusal("DELETE", "/api/cart/42")).isNull();
        assertThat(rules.replayRefusal("GET", "/api/anything/at/all")).isNull();
        assertThat(rules.replayRefusal("POST", "/api/orders/7/refund")).contains("not in causeline.mcp.replay.allow");
        // Listing something means GET is no longer allowed everywhere by default.
        assertThat(rules.replayRefusal("GET", "/health")).isNotNull();
    }

    @Test
    void denyWinsOverAllow() {
        McpRules rules = rules(Map.of(
                "mcp.replay.allow[0]", "* /api/**",
                "mcp.replay.deny[0]", "* /api/admin/**",
                "mcp.replay.deny[1]", "DELETE /**"));

        assertThat(rules.replayRefusal("POST", "/api/orders")).isNull();
        assertThat(rules.replayRefusal("GET", "/api/admin/users"))
                .contains("causeline.mcp.replay.deny", "\"* /api/admin/**\"");
        assertThat(rules.replayRefusal("DELETE", "/api/orders/7")).contains("\"DELETE /**\"");
    }

    @Test
    void hiddenPathsCanNeverBeReplayed() {
        McpRules rules = rules(Map.of("mcp.hide[0]", "/api/payroll/**", "mcp.replay.allow[0]", "* /**"));

        assertThat(rules.isHidden("/api/payroll/2026/10")).isTrue();
        assertThat(rules.isHidden("/api/orders")).isFalse();
        assertThat(rules.isHidden(null)).isFalse();
        assertThat(rules.replayRefusal("GET", "/api/payroll/2026")).contains("causeline.mcp.hide");
    }

    @Test
    void badPatternsFailAtStartupNamingTheSetting() {
        assertThatThrownBy(() -> rules(Map.of("mcp.replay.allow[0]", "/api/orders")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("causeline.mcp.replay.allow");
        assertThatThrownBy(() -> rules(Map.of("mcp.replay.deny[0]", "GET api/orders")))
                .hasMessageContaining("causeline.mcp.replay.deny").hasMessageContaining("must start with /");
        assertThatThrownBy(() -> rules(Map.of("mcp.hide[0]", "/api/**/x/**")))
                .hasMessageContaining("causeline.mcp.hide");
    }

    private static McpRules rules(Map<String, String> overrides) {
        return new McpRules(TestProperties.bind(overrides).mcp());
    }
}
