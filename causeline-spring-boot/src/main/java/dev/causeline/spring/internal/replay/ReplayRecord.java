// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import java.util.Map;

/**
 * What is needed to send a captured request again. Kept in memory only and never returned by the
 * API: the UI sees the route template and whether a body was captured, not the values.
 *
 * @param query      the raw query string; null when not captured or absent
 * @param body       the redacted body; null when not captured or absent
 * @param hadQuery   the original request had a query string (captured or not)
 * @param hadBody    the original request had a body (captured or not)
 * @param headers    only allowlisted, non-credential headers
 */
public record ReplayRecord(
        String traceId,
        String spanId,
        String method,
        String path,
        String query,
        Map<String, String> headers,
        byte[] body,
        boolean hadQuery,
        boolean hadBody,
        boolean hadIdempotencyKey) {

    public boolean isUnsafe() {
        return !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));
    }

    public boolean bodyMissing() {
        return hadBody && body == null;
    }

    public boolean queryMissing() {
        return hadQuery && query == null;
    }
}
