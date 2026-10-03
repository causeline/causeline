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
        /**
         * The same query run again and again under one parent: five or more identical SELECTs (the
         * classic N+1 of lazy loading), or ten or more of any other statement.
         */
        REPEATED_QUERY,
        /** The browser took 100 ms or more to update state after a response arrived. */
        SLOW_CLIENT_HANDLING,
        /** The application caught and logged an exception; the request carried on, possibly degraded. */
        HANDLED_EXCEPTION
    }
}
