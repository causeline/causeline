// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.Baselines;
import dev.causeline.core.Span;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceComparison;
import dev.causeline.core.TraceFile;
import dev.causeline.core.TraceStore;
import dev.causeline.core.TraceSummary;
import dev.causeline.core.TraceView;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.export.SpanForwarder;
import dev.causeline.spring.internal.export.UpstreamForwarder;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    /** A downstream Causeline sends up to 500 spans per request. */
    static final int MAX_PEER_SPANS_PER_REQUEST = 2_000;

    private final TraceStore store;
    private final Function<String, Optional<String>> replayOf;
    private final CauselineStats stats;
    private final SpanForwarder forwarder;
    private final String appName;
    private final Status.Otlp otlp;
    private Status.Upstream upstream = Status.Upstream.disabled();
    private final SpanRedactor redactor;
    private final Set<String> imported = ConcurrentHashMap.newKeySet();
    private final SourceLocator sources = new SourceLocator(java.nio.file.Path.of(System.getProperty("user.dir", ".")));

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
        List<TraceSummary> summaries = store.snapshot().stream()
                .map(spans -> TraceAssembler.assemble(spans.getFirst().traceId(), spans).summary())
                .map(summary -> summary.withReplayOf(replayOf.apply(summary.traceId()).orElse(null))
                        .withImported(imported.contains(summary.traceId())))
                .toList();
        return Baselines.apply(summaries).stream()
                .sorted(Comparator.comparingLong(TraceSummary::startTimeUnixNano).reversed())
                .toList();
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<TraceView> trace(@PathVariable("traceId") String traceId) {
        return store.get(traceId)
                .map(spans -> ResponseEntity.ok(TraceAssembler.assemble(traceId, spans)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Two runs compared span by span, e.g. yesterday's fast checkout and today's slow one.
     * {@code a} is shown as the baseline and {@code b} as the run being explained.
     */
    @GetMapping("/compare")
    public ResponseEntity<TraceComparison.Result> compare(@RequestParam("a") String a, @RequestParam("b") String b) {
        Optional<List<Span>> first = store.get(a);
        Optional<List<Span>> second = store.get(b);
        if (first.isEmpty() || second.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TraceComparison.compareTraces(TraceAssembler.assemble(a, first.get()),
                TraceAssembler.assemble(b, second.get())));
    }

    /**
     * Traces with a span whose name or any captured value contains {@code q}, ignoring case: an
     * order ID in a body, a table in SQL, a header value. Newest first.
     */
    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam("q") String q) {
        String needle = q.strip().toLowerCase(java.util.Locale.ROOT);
        if (needle.length() < 2) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (List<Span> spans : store.snapshot()) {
            List<SearchHit.Match> matches = new ArrayList<>();
            // Requests first: a match in what was sent says more than one deep inside the trace.
            List<Span> ordered = spans.stream()
                    .sorted(Comparator.comparing((Span span) -> span.kind() != dev.causeline.core.SpanKind.REQUEST))
                    .toList();
            for (Span span : ordered) {
                if (span.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    matches.add(new SearchHit.Match(span.spanId(), span.name(), "name", excerpt(span.name(), needle)));
                }
                for (Map.Entry<String, String> attribute : span.attributes().entrySet()) {
                    if (matches.size() >= SearchHit.MAX_MATCHES) {
                        break;
                    }
                    String value = attribute.getValue();
                    if (value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        matches.add(new SearchHit.Match(span.spanId(), span.name(), attribute.getKey(),
                                excerpt(value, needle)));
                    }
                }
                if (matches.size() >= SearchHit.MAX_MATCHES) {
                    break;
                }
            }
            if (!matches.isEmpty()) {
                hits.add(new SearchHit(spans.getFirst().traceId(),
                        spans.stream().mapToLong(Span::startTimeUnixNano).min().orElse(0), matches));
            }
        }
        hits.sort(Comparator.comparingLong(SearchHit::startTimeUnixNano).reversed());
        return hits.size() > SearchHit.MAX_TRACES ? hits.subList(0, SearchHit.MAX_TRACES) : hits;
    }

    /** Where an application class's source file is, so the UI can open it in the editor. Paths only. */
    @GetMapping("/source")
    public ResponseEntity<SourceLocator.Location> source(@RequestParam("class") String className,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "line", defaultValue = "0") int line) {
        return sources.locate(className, method, line)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The text around the first occurrence, so the trace list can show why a trace matched. */
    static String excerpt(String value, String needle) {
        String flat = value.replaceAll("\\s+", " ");
        int at = flat.toLowerCase(java.util.Locale.ROOT).indexOf(needle);
        if (at < 0) {
            return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
        }
        int from = Math.max(0, at - 30);
        int to = Math.min(flat.length(), at + needle.length() + 40);
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }

    /** A trace that matched a search, with up to {@value #MAX_MATCHES} places it matched. */
    public record SearchHit(String traceId, long startTimeUnixNano, List<Match> matches) {

        static final int MAX_MATCHES = 3;
        static final int MAX_TRACES = 100;

        /** @param field "name", or the attribute that matched, e.g. {@code http.request.body} */
        public record Match(String spanId, String spanName, String field, String excerpt) {
        }
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
        stats.browserSpansAccepted(accepted);
        forwarder.forward(accepted);
        int rejected = spans.size() - accepted.size();
        stats.browserSpansRejected.addAndGet(rejected);
        return ResponseEntity.accepted().body(Map.of("accepted", accepted.size(), "rejected", rejected));
    }

    /**
     * Spans from a downstream service's Causeline ({@code causeline.export.upstream} there), so a
     * call that crosses services shows as one trace here. Needs this app's access token. Received
     * spans are marked, so they are never forwarded on again.
     */
    @PostMapping("/peer-spans")
    public ResponseEntity<Map<String, Integer>> peerSpans(@RequestBody List<Span> spans) {
        if (spans.size() > MAX_PEER_SPANS_PER_REQUEST) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        List<Span> accepted = new ArrayList<>(spans.size());
        for (Span span : spans) {
            if (span == null) {
                continue;
            }
            Map<String, String> attributes = new java.util.LinkedHashMap<>(span.attributes());
            attributes.put(UpstreamForwarder.FORWARDED_ATTRIBUTE, "true");
            accepted.add(new Span(span.traceId(), span.spanId(), span.parentSpanId(), span.kind(), span.name(),
                    span.source(), span.startTimeUnixNano(), span.durationNanos(), span.status(), attributes));
        }
        store.addAll(accepted);
        stats.peerSpansReceived.addAndGet(accepted.size());
        forwarder.forward(accepted); // OTLP may export them; the upstream forwarder skips them
        return ResponseEntity.accepted().body(Map.of("accepted", accepted.size()));
    }

    /** Where data was lost, so gaps in traces are explained. */
    @GetMapping("/status")
    public Status status() {
        return new Status(store.size(), store.estimatedBytes(), store.maxBytes(), store.evictedTraces(),
                stats.serverSpansDropped.get(), stats.browserSpansDropped.get(), stats.browserSpansRejected.get(),
                otlp.withCounts(stats), Status.Onboarding.from(appName, stats), upstream.withCounts(stats));
    }

    /** Reports the upstream Causeline in the status; host only, never the token. */
    public void setUpstream(String host) {
        this.upstream = new Status.Upstream(true, host, 0, 0, 0, 0);
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
            long browserSpansDropped, long browserSpansRejected, Otlp otlp, Onboarding onboarding, Upstream upstream) {

        /**
         * The Causeline this service sends its spans to, and spans received from downstream services.
         *
         * @param host where spans go; the token is never shown
         */
        public record Upstream(boolean enabled, String host, long sent, long dropped, long failedRequests,
                long received) {

            static Upstream disabled() {
                return new Upstream(false, null, 0, 0, 0, 0);
            }

            Upstream withCounts(CauselineStats stats) {
                return new Upstream(enabled, host, stats.upstreamSent.get(), stats.upstreamDropped.get(),
                        stats.upstreamFailedRequests.get(), stats.peerSpansReceived.get());
            }
        }

        /**
         * What has arrived since the application started, for the UI's first-run checklist.
         *
         * @param lastServerSpanAt  epoch milliseconds, or null if no server span has been stored
         * @param lastBrowserSpanAt epoch milliseconds, or null if the React SDK has not sent anything
         */
        public record Onboarding(String appName, long serverSpans, long browserSpans, long namedActions,
                Long lastServerSpanAt, Long lastBrowserSpanAt) {

            static Onboarding from(String appName, CauselineStats stats) {
                return new Onboarding(appName, stats.serverSpansStored.get(), stats.browserSpansAccepted.get(),
                        stats.browserActionsSeen.get(), stats.lastServerSpanAt == 0 ? null : stats.lastServerSpanAt,
                        stats.lastBrowserSpanAt == 0 ? null : stats.lastBrowserSpanAt);
            }
        }

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
