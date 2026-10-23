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
        HANDLED_EXCEPTION,
        /** One request wrote to the database in two or more separate transactions, so the writes are not atomic. */
        WRITES_NOT_ATOMIC,
        /**
         * Queries ran in the controller or while the response was written, after the request's
         * transaction had ended: lazy loading kept alive by open-session-in-view.
         */
        QUERY_AFTER_TRANSACTION,
        /** A transaction, and its database connection, stayed open for 100 ms or more of HTTP calls. */
        TRANSACTION_ACROSS_CALL,
        /** Two or more GET requests of one action ran one after another instead of together. */
        REQUEST_WATERFALL,
        /** One component rendered five or more times during one action. */
        RENDER_STORM,
        /** State was set after its component had unmounted: a request or timer outlived it. */
        STATE_UPDATE_AFTER_UNMOUNT
    }
}
