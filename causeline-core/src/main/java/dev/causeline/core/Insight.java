// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

/**
 * A deterministic finding about a trace, attached to the span it is about.
 *
 * @param sharePercent the span's self time as a share of the trace duration; 0 when not meaningful
 */
public record Insight(Rule rule, String spanId, String label, int sharePercent) {

    public enum Rule {
        /** One span's self time is at least half of the whole trace. */
        PRIMARY_BOTTLENECK,
        /** Self time of at least a quarter of the trace and at least 100 ms. */
        NOTABLE,
        /** Ten or more identical queries under one parent: the classic N+1 pattern. */
        REPEATED_QUERY,
        /** The browser took 100 ms or more to update state after a response arrived. */
        SLOW_CLIENT_HANDLING,
        /** The application caught and logged an exception; the request carried on, possibly degraded. */
        HANDLED_EXCEPTION
    }
}
