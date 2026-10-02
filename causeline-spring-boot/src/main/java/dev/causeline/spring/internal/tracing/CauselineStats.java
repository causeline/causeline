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

    /** Spans sent to the upstream Causeline (causeline.export.upstream). */
    public final AtomicLong upstreamSent = new AtomicLong();
    /** Spans not sent upstream: queue full or the upstream unreachable. */
    public final AtomicLong upstreamDropped = new AtomicLong();
    /** Requests to the upstream Causeline that failed. */
    public final AtomicLong upstreamFailedRequests = new AtomicLong();
    /** Spans received from downstream services' Causeline. */
    public final AtomicLong peerSpansReceived = new AtomicLong();

    // Onboarding signals: what has ever arrived, so the UI can tell a new user what is still missing.

    /** Server spans stored since start. */
    public final AtomicLong serverSpansStored = new AtomicLong();
    /** Browser spans accepted at ingest since start: proof that the React SDK is connected. */
    public final AtomicLong browserSpansAccepted = new AtomicLong();
    /** Browser UI_ACTION spans, i.e. actions named with trace() or useTracedCallback(). */
    public final AtomicLong browserActionsSeen = new AtomicLong();
    /** Epoch milliseconds of the last stored server span, or 0. */
    public volatile long lastServerSpanAt;
    /** Epoch milliseconds of the last accepted browser span, or 0. */
    public volatile long lastBrowserSpanAt;

    public void serverSpansStored(int count) {
        if (count > 0) {
            serverSpansStored.addAndGet(count);
            lastServerSpanAt = System.currentTimeMillis();
        }
    }

    public void browserSpansAccepted(java.util.Collection<dev.causeline.core.Span> spans) {
        if (spans.isEmpty()) {
            return;
        }
        browserSpansAccepted.addAndGet(spans.size());
        browserActionsSeen.addAndGet(spans.stream().filter(s -> s.kind() == dev.causeline.core.SpanKind.UI_ACTION).count());
        lastBrowserSpanAt = System.currentTimeMillis();
    }
}
