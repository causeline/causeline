// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.Span;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceComparison;
import dev.causeline.core.TraceStore;
import dev.causeline.core.TraceView;
import dev.causeline.spring.internal.replay.ReplayRecord;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplayService.ReplayException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Replay and comparison endpoints behind the UI. Protected by the access token like all of /causeline/api. */
@RestController
@RequestMapping("/causeline/api")
public class ReplayApiController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReplayService replays;
    private final TraceStore traces;

    public ReplayApiController(ReplayService replays, TraceStore traces) {
        this.replays = replays;
        this.traces = traces;
    }

    /** Targets by name; base URLs are shown, credentials never are. */
    @GetMapping("/replay/targets")
    public List<TargetInfo> targets() {
        return replays.targets().entrySet().stream()
                .map(e -> new TargetInfo(e.getKey(),
                        replays.isLocal(e.getKey()) ? "this application" : e.getValue().baseUrl(),
                        e.getValue().auth().type().name().toLowerCase(),
                        replays.isLocal(e.getKey()) || e.getValue().causelineToken() != null,
                        replays.sendsOriginalCredentials()))
                .sorted((a, b) -> a.name().equals(ReplayService.LOCAL_TARGET) ? -1
                        : b.name().equals(ReplayService.LOCAL_TARGET) ? 1 : a.name().compareTo(b.name()))
                .toList();
    }

    /** The server requests in a trace that can be replayed, described without revealing captured values. */
    @GetMapping("/traces/{traceId}/replayable")
    public List<Replayable> replayable(@PathVariable("traceId") String traceId) {
        List<Span> spans = traces.get(traceId).orElse(List.of());
        return spans.stream()
                .map(span -> replays.record(traceId, span.spanId()).map(record -> describe(span, record)))
                .flatMap(Optional::stream)
                .toList();
    }

    @PostMapping("/replays")
    public ReplayService.Outcome replay(@RequestBody ReplayRequest request) {
        return replays.replay(request.traceId(), request.spanId(), request.target(), request.confirm(), request.body());
    }

    /**
     * Compares the original request with its replay. Answers 404 until the replay's spans have
     * arrived; the UI retries.
     */
    @GetMapping("/replays/compare")
    public ResponseEntity<Comparison> compare(@RequestParam("traceId") String traceId,
            @RequestParam("spanId") String spanId, @RequestParam("replayTraceId") String replayTraceId,
            @RequestParam("target") String target) {
        Optional<List<Span>> originalSpans = traces.get(traceId);
        if (originalSpans.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TraceView original = TraceAssembler.assemble(traceId, originalSpans.get());
        Optional<TraceView> replay;
        if (replays.isLocal(target)) {
            replay = traces.get(replayTraceId).map(spans -> TraceAssembler.assemble(replayTraceId, spans));
        } else {
            if (!replays.targets().containsKey(target) || replays.targets().get(target).causelineToken() == null) {
                return ResponseEntity.ok(new Comparison(false, null));
            }
            replay = replays.fetchRemoteTrace(target, replayTraceId).map(json -> JSON.readValue(json, TraceView.class));
        }
        return replay
                .map(view -> ResponseEntity.ok(new Comparison(true, TraceComparison.compare(original, spanId, view))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(ReplayException.class)
    ResponseEntity<Map<String, String>> refused(ReplayException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    private static Replayable describe(Span span, ReplayRecord record) {
        String route = span.attributes().getOrDefault("http.route", record.path());
        return new Replayable(span.spanId(), record.method(), route, record.isUnsafe(),
                record.hadBody(), record.body() == null ? 0 : record.body().length, record.bodyMissing(),
                record.queryMissing());
    }

    /** @param sendsOriginalCredentials whether the original request's credential headers are resent */
    public record TargetInfo(String name, String location, String auth, boolean comparable,
            boolean sendsOriginalCredentials) {
    }

    /**
     * @param bodyBytes size of the captured, redacted body; its content is never returned
     */
    public record Replayable(String spanId, String method, String route, boolean unsafe, boolean hasBody,
            int bodyBytes, boolean bodyMissing, boolean queryMissing) {
    }

    /** @param body optional body typed by the developer when none was captured */
    public record ReplayRequest(String traceId, String spanId, String target, boolean confirm, String body) {
    }

    /** @param instrumented false when the target has no Causeline token configured, so only status and time are known */
    public record Comparison(boolean instrumented, TraceComparison.Result result) {
    }
}
