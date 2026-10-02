// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.Span;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceFile;
import dev.causeline.core.TraceStore;
import dev.causeline.core.TraceSummary;
import dev.causeline.core.TraceView;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.export.SpanForwarder;
import dev.causeline.spring.internal.tracing.CauselineStats;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JSON API behind the Causeline UI, plus the ingest endpoint for browser spans. */
@RestController
@RequestMapping("/causeline/api")
public class CauselineApiController {

    /** Upper bound on spans per ingest call; the SDK sends at most 50 at a time. */
    static final int MAX_SPANS_PER_REQUEST = 500;
    /** The SDK's own buffer holds 500 spans; a larger drop count can't be genuine. */
    static final int MAX_REPORTED_DROPS = 100_000;
    static final String DROPPED_HEADER = "X-Causeline-Dropped";

    private final TraceStore store;
    private final Function<String, Optional<String>> replayOf;
    private final CauselineStats stats;
    private final SpanForwarder forwarder;
    private final String appName;
    private final Status.Otlp otlp;
    private final SpanRedactor redactor;
    private final Set<String> imported = ConcurrentHashMap.newKeySet();

    /**
     * @param replayOf   the original trace a trace replays, if any
     * @param forwarder  receives accepted browser spans, e.g. for OTLP export
     * @param otlp       OTLP export settings, for the status report; host only, never headers
     */
    public CauselineApiController(TraceStore store, Function<String, Optional<String>> replayOf, CauselineStats stats,
            SpanForwarder forwarder, String appName, Status.Otlp otlp, SpanRedactor redactor) {
        this.redactor = redactor;
        this.store = store;
        this.replayOf = replayOf;
        this.stats = stats;
        this.forwarder = forwarder;
        this.appName = appName;
        this.otlp = otlp;
    }

    @GetMapping("/traces")
    public List<TraceSummary> traces() {
        return store.snapshot().stream()
                .map(spans -> TraceAssembler.assemble(spans.getFirst().traceId(), spans).summary())
                .map(summary -> summary.withReplayOf(replayOf.apply(summary.traceId()).orElse(null))
                        .withImported(imported.contains(summary.traceId())))
                .sorted(Comparator.comparingLong(TraceSummary::startTimeUnixNano).reversed())
                .toList();
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<TraceView> trace(@PathVariable("traceId") String traceId) {
        return store.get(traceId)
                .map(spans -> ResponseEntity.ok(TraceAssembler.assemble(traceId, spans)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The trace as a file to attach to a bug report, with secrets redacted unless export.redact-secrets=false. */
    @GetMapping("/traces/{traceId}/export")
    public ResponseEntity<TraceFile> export(@PathVariable("traceId") String traceId) {
        return store.get(traceId)
                .map(spans -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"causeline-trace-" + traceId.substring(0, 8) + ".json\"")
                        .body(new TraceFile(TraceFile.FORMAT, Span.SCHEMA_VERSION, Instant.now().toString(), appName,
                                spans.stream().map(redactor::redact).toList())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Loads an exported trace. It is pinned, so it stays until the application restarts. */
    @PostMapping("/traces/import")
    public ResponseEntity<Map<String, String>> importTrace(@RequestBody TraceFile file) {
        String traceId;
        try {
            traceId = file.validate();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (store.contains(traceId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This trace is already loaded.", "traceId", traceId));
        }
        store.addAll(file.spans());
        store.pin(traceId);
        imported.add(traceId);
        return ResponseEntity.ok(Map.of("traceId", traceId));
    }

    @PostMapping("/spans")
    public ResponseEntity<Map<String, Integer>> ingest(@RequestBody List<BrowserSpan> spans,
            @RequestHeader(value = DROPPED_HEADER, required = false) String droppedBySdk) {
        recordSdkDrops(droppedBySdk);
        if (spans.size() > MAX_SPANS_PER_REQUEST) {
            stats.browserSpansRejected.addAndGet(spans.size());
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        List<Span> accepted = new ArrayList<>();
        for (BrowserSpan span : spans) {
            try {
                accepted.add(span.toSpan());
            } catch (IllegalArgumentException | NullPointerException e) {
                // Malformed spans are dropped; the rest of the batch is still useful.
            }
        }
        store.addAll(accepted);
        forwarder.forward(accepted);
        int rejected = spans.size() - accepted.size();
        stats.browserSpansRejected.addAndGet(rejected);
        return ResponseEntity.accepted().body(Map.of("accepted", accepted.size(), "rejected", rejected));
    }

    /** Where data was lost, so gaps in traces are explained. */
    @GetMapping("/status")
    public Status status() {
        return new Status(store.size(), store.estimatedBytes(), store.maxBytes(), store.evictedTraces(),
                stats.serverSpansDropped.get(), stats.browserSpansDropped.get(), stats.browserSpansRejected.get(),
                otlp.withCounts(stats));
    }

    private void recordSdkDrops(String header) {
        if (header == null) {
            return;
        }
        try {
            int dropped = Integer.parseInt(header.trim());
            if (dropped > 0 && dropped <= MAX_REPORTED_DROPS) {
                stats.browserSpansDropped.addAndGet(dropped);
            }
        } catch (NumberFormatException e) {
            // Ignore nonsense; the header is informational only.
        }
    }

    public record Status(int traces, long estimatedBytes, long maxBytes, long evictedTraces, long serverSpansDropped,
            long browserSpansDropped, long browserSpansRejected, Otlp otlp) {

        /** @param endpointHost where spans go; the full URL and headers are never shown */
        public record Otlp(boolean enabled, String endpointHost, long exported, long dropped, long failedRequests) {

            public static Otlp disabled() {
                return new Otlp(false, null, 0, 0, 0);
            }

            Otlp withCounts(CauselineStats stats) {
                return enabled
                        ? new Otlp(true, endpointHost, stats.otlpExported.get(), stats.otlpDropped.get(),
                                stats.otlpFailedRequests.get())
                        : this;
            }
        }
    }
}
