// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded in-memory store of spans grouped by trace. It holds at most {@code maxTraces} traces
 * and about {@code maxBytes} of span data (estimated). When either limit is exceeded, the trace
 * that was least recently added to or read is evicted first, except pinned traces (for example
 * the original of a replay), which stay until unpinned. Operations are cheap and do no I/O, so
 * they are safe to call from span exporters.
 */
public final class TraceStore {

    public static final int DEFAULT_MAX_TRACES = 1_000;
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;
    public static final int MAX_PINNED = 50;

    private final int maxTraces;
    private final long maxBytes;
    // Access order gives least-recently-used iteration order.
    private final LinkedHashMap<String, List<Span>> traces = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Long> traceBytes = new HashMap<>();
    private final Set<String> pinned = new HashSet<>();
    private long totalBytes;
    private long evictedTraces;

    public TraceStore() {
        this(DEFAULT_MAX_TRACES, DEFAULT_MAX_BYTES);
    }

    public TraceStore(int maxTraces) {
        this(maxTraces, DEFAULT_MAX_BYTES);
    }

    public TraceStore(int maxTraces, long maxBytes) {
        if (maxTraces < 1) {
            throw new IllegalArgumentException("maxTraces must be at least 1: " + maxTraces);
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be at least 1: " + maxBytes);
        }
        this.maxTraces = maxTraces;
        this.maxBytes = maxBytes;
    }

    public synchronized void add(Span span) {
        traces.computeIfAbsent(span.traceId(), id -> new ArrayList<>()).add(span);
        long size = estimateBytes(span);
        traceBytes.merge(span.traceId(), size, Long::sum);
        totalBytes += size;
        evict();
    }

    public synchronized void addAll(Collection<Span> spans) {
        spans.forEach(this::add);
    }

    public synchronized Optional<List<Span>> get(String traceId) {
        List<Span> spans = traces.get(traceId);
        return spans == null ? Optional.empty() : Optional.of(List.copyOf(spans));
    }

    public synchronized boolean contains(String traceId) {
        return traces.containsKey(traceId);
    }

    /** Snapshot of every stored trace, in no particular order. */
    public synchronized List<List<Span>> snapshot() {
        return traces.values().stream().map(List::copyOf).toList();
    }

    public synchronized int size() {
        return traces.size();
    }

    /** Estimated bytes held, the figure {@code maxBytes} is compared against. */
    public synchronized long estimatedBytes() {
        return totalBytes;
    }

    public synchronized long evictedTraces() {
        return evictedTraces;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * Protects a trace from eviction. Returns false when the trace is unknown; at the pin limit
     * the oldest pin is released to make room.
     */
    public synchronized boolean pin(String traceId) {
        if (!traces.containsKey(traceId)) {
            return false;
        }
        if (pinned.size() >= MAX_PINNED && !pinned.contains(traceId)) {
            pinned.remove(pinned.iterator().next());
        }
        pinned.add(traceId);
        return true;
    }

    public synchronized void unpin(String traceId) {
        pinned.remove(traceId);
    }

    public synchronized boolean isPinned(String traceId) {
        return pinned.contains(traceId);
    }

    /**
     * A deliberately rough, conservative estimate of a span's heap footprint: object headers and
     * fields, plus two bytes per character of its strings. Good enough to keep memory bounded.
     */
    static long estimateBytes(Span span) {
        long bytes = 240;
        bytes += 2L * (span.name().length() + span.source().length());
        for (Map.Entry<String, String> attribute : span.attributes().entrySet()) {
            bytes += 96 + 2L * (attribute.getKey().length() + attribute.getValue().length());
        }
        return bytes;
    }

    private void evict() {
        Iterator<Map.Entry<String, List<Span>>> it = traces.entrySet().iterator();
        while ((traces.size() > maxTraces || totalBytes > maxBytes) && it.hasNext()) {
            String traceId = it.next().getKey();
            if (!pinned.contains(traceId)) {
                it.remove();
                totalBytes -= traceBytes.remove(traceId);
                evictedTraces++;
            }
        }
    }
}
