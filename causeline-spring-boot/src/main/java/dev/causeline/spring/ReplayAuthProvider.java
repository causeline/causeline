// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring;

import java.util.Map;

/**
 * Supplies credentials for replay targets configured with {@code auth.type: provider}, for example
 * a freshly signed JWT for a test user. Implement it as a bean in your application.
 *
 * <pre>{@code
 * @Bean
 * ReplayAuthProvider replayAuth(DevTokens tokens) {
 *     return target -> Map.of("Authorization", "Bearer " + tokens.forUser("qa-user"));
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ReplayAuthProvider {

    /** Headers to add to a replay sent to the named target. */
    Map<String, String> headers(String targetName);
}
