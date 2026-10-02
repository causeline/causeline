// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded, least-recently-used store of replay records, keyed by trace and server span. */
public final class ReplayStore {

    private final Map<String, ReplayRecord> records;
    /** Replay trace ID → the original trace it replays. */
    private final Map<String, String> replays;

    public ReplayStore(int maxRecords) {
        this.records = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ReplayRecord> eldest) {
                return size() > maxRecords;
            }
        };
        this.replays = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxRecords;
            }
        };
    }

    public synchronized void markReplay(String replayTraceId, String originalTraceId) {
        replays.put(replayTraceId, originalTraceId);
    }

    /** The original trace that {@code traceId} replays, if it is a replay. */
    public synchronized Optional<String> replayOf(String traceId) {
        return Optional.ofNullable(replays.get(traceId));
    }

    public synchronized void put(ReplayRecord record) {
        records.put(key(record.traceId(), record.spanId()), record);
    }

    public synchronized Optional<ReplayRecord> get(String traceId, String spanId) {
        return Optional.ofNullable(records.get(key(traceId, spanId)));
    }

    public synchronized List<ReplayRecord> forTrace(String traceId) {
        return records.values().stream().filter(r -> r.traceId().equals(traceId)).toList();
    }

    private static String key(String traceId, String spanId) {
        return traceId + "|" + spanId;
    }
}
