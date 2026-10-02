// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for data Causeline had to drop, so gaps in a trace are explained rather than silent.
 * Shown by {@code GET /causeline/api/status} and as a warning in the UI.
 */
public final class CauselineStats {

    /** Server spans lost because the export queue was full. */
    public final AtomicLong serverSpansDropped = new AtomicLong();
    /** Browser spans the SDK dropped because its buffer was full, as reported by the SDK. */
    public final AtomicLong browserSpansDropped = new AtomicLong();
    /** Browser spans refused at ingest as malformed or with a server-side kind. */
    public final AtomicLong browserSpansRejected = new AtomicLong();
    /** Spans successfully sent to the OTLP endpoint. */
    public final AtomicLong otlpExported = new AtomicLong();
    /** Spans not sent to the OTLP endpoint because its queue was full. */
    public final AtomicLong otlpDropped = new AtomicLong();
    /** OTLP requests that failed (unreachable endpoint or non-2xx answer). */
    public final AtomicLong otlpFailedRequests = new AtomicLong();
}
