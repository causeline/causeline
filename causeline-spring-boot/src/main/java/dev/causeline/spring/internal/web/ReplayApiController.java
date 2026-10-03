// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.core.Span;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceComparison;
import dev.causeline.core.TraceStore;
import dev.causeline.core.TraceView;
import dev.causeline.spring.internal.replay.ReplayRecord;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.replay.ReplaySessions;
import dev.causeline.spring.internal.replay.ReplayService.ReplayException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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

    /**
     * Replays and remote comparisons wait on the network for up to 30 s, so they run here rather
     * than on a request thread (or, in WebFlux, on the event loop). Both are started by hand from
     * the UI, so a thread per call is fine.
     */
    private static final Executor NETWORK = Executors.newVirtualThreadPerTaskExecutor();

    private final ReplayService replays;
    private final TraceStore traces;
    private final ReplaySessions sessions;
    private final JsonMapper mapper;

    public ReplayApiController(ReplayService replays, TraceStore traces) {
        this(replays, traces, null, JSON);
    }

    /**
     * @param sessions replays that pause at chosen steps; null where requests can't be paused (WebFlux)
     * @param mapper   the application's JSON mapper, to turn edited arguments into their real types
     */
    public ReplayApiController(ReplayService replays, TraceStore traces, ReplaySessions sessions, JsonMapper mapper) {
        this.replays = replays;
        this.traces = traces;
        this.sessions = sessions;
        this.mapper = mapper;
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
    public CompletableFuture<ReplayService.Outcome> replay(@RequestBody ReplayRequest request) {
        return CompletableFuture.supplyAsync(() -> replays.replay(request.traceId(), request.spanId(),
                request.target(), request.confirm(), request.body()), NETWORK);
    }

    /**
     * Compares the original request with its replay. Answers 404 until the replay's spans have
     * arrived; the UI retries.
     */
    @GetMapping("/replays/compare")
    public CompletableFuture<ResponseEntity<Comparison>> compare(@RequestParam("traceId") String traceId,
            @RequestParam("spanId") String spanId, @RequestParam("replayTraceId") String replayTraceId,
            @RequestParam("target") String target) {
        return CompletableFuture.supplyAsync(() -> compareNow(traceId, spanId, replayTraceId, target), NETWORK);
    }

    private ResponseEntity<Comparison> compareNow(String traceId, String spanId, String replayTraceId, String target) {
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

    /**
     * Starts a replay of this application that stops at the chosen steps (controller and
     * {@code @Observed} service spans of the original), so their arguments can be changed.
     */
    @PostMapping("/replay-sessions")
    public SessionView startSession(@RequestBody SessionRequest request) {
        if (sessions == null) {
            throw new ReplayException(400, "Pausing a replay needs a Spring MVC application: in WebFlux a pause would hold up the event loop.");
        }
        if (!replays.isLocal(request.target() == null ? ReplayService.LOCAL_TARGET : request.target())) {
            throw new ReplayException(400, "Only a replay to this application (local) can pause at steps.");
        }
        List<ReplaySessions.Breakpoint> breakpoints = breakpoints(request.traceId(), request.spanId(),
                request.breakpoints() == null ? List.of() : request.breakpoints());
        replays.check(request.traceId(), request.spanId(), ReplayService.LOCAL_TARGET, request.confirmed(), request.body());
        ReplaySessions.Session session = sessions.create(request.traceId(), request.spanId(), breakpoints);
        java.time.Duration timeout = sessions.pauseTimeout().multipliedBy(Math.max(1, breakpoints.size()))
                .plus(java.time.Duration.ofSeconds(30));
        CompletableFuture.runAsync(() -> {
            try {
                session.finished(replays.replay(request.traceId(), request.spanId(), ReplayService.LOCAL_TARGET,
                        request.confirmed(), request.body(), Map.of(ReplaySessions.HEADER, session.id()), timeout,
                        session::started));
            } catch (RuntimeException e) {
                session.failed(e.getMessage());
            }
        }, NETWORK);
        return SessionView.of(session);
    }

    @GetMapping("/replay-sessions/{id}")
    public ResponseEntity<SessionView> session(@PathVariable("id") String id) {
        return sessionFor(id).map(s -> ResponseEntity.ok(SessionView.of(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Continues a paused replay. Arguments whose JSON changed are converted to the parameter's type
     * with the application's JSON mapper; the rest are passed on as they were.
     */
    @PostMapping("/replay-sessions/{id}/continue")
    public ResponseEntity<SessionView> resume(@PathVariable("id") String id, @RequestBody ContinueRequest request) {
        ReplaySessions.Session session = sessionFor(id).orElse(null);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        ReplaySessions.Paused paused = session.paused();
        if (paused == null) {
            throw new ReplayException(409, "This replay is not paused.");
        }
        Object[] values = paused.values().clone();
        boolean changed = false;
        Map<String, String> edits = request.arguments() == null ? Map.of() : request.arguments();
        for (int i = 0; i < paused.arguments().size(); i++) {
            ReplaySessions.Argument argument = paused.arguments().get(i);
            String json = edits.get(argument.name());
            if (json == null || json.equals(argument.json()) || !argument.editable()) {
                continue;
            }
            try {
                values[i] = mapper.readValue(json,
                        mapper.getTypeFactory().constructType(paused.method().getGenericParameterTypes()[i]));
                changed = true;
            } catch (RuntimeException e) {
                throw new ReplayException(400, "Argument '" + argument.name() + "' is not a valid "
                        + simpleType(argument.type()) + ": " + firstLine(e.getMessage()));
            }
        }
        if (!session.resume(changed ? values : null, request.skipRemaining(), changed)) {
            throw new ReplayException(409, "This replay is not paused.");
        }
        return ResponseEntity.ok(SessionView.of(session));
    }

    private java.util.Optional<ReplaySessions.Session> sessionFor(String id) {
        return sessions == null ? java.util.Optional.empty() : sessions.get(id);
    }

    /** Each chosen span of the original, as "the n-th call of Class.method" within the replayed request. */
    private List<ReplaySessions.Breakpoint> breakpoints(String traceId, String requestSpanId, List<String> spanIds) {
        List<Span> spans = traces.get(traceId).orElseThrow(() -> new ReplayException(404, "Trace not found."));
        Map<String, List<Span>> children = new java.util.HashMap<>();
        spans.forEach(s -> children.computeIfAbsent(String.valueOf(s.parentSpanId()), k -> new java.util.ArrayList<>()).add(s));
        List<Span> inRequest = new java.util.ArrayList<>();
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(requestSpanId));
        while (!todo.isEmpty()) {
            for (Span child : children.getOrDefault(todo.pop(), List.of())) {
                inRequest.add(child);
                todo.push(child.spanId());
            }
        }
        inRequest.sort(java.util.Comparator.comparingLong(Span::startTimeUnixNano));
        List<ReplaySessions.Breakpoint> result = new java.util.ArrayList<>();
        for (String spanId : spanIds) {
            Span chosen = inRequest.stream().filter(s -> s.spanId().equals(spanId)).findFirst()
                    .orElseThrow(() -> new ReplayException(400, "That step is not part of the replayed request."));
            String className = chosen.attributes().get("code.namespace");
            String method = chosen.attributes().get("code.function");
            if (className == null || method == null) {
                throw new ReplayException(400, chosen.name() + " can't be paused: only controller and @Observed service methods can.");
            }
            int occurrence = 0;
            for (Span s : inRequest) {
                if (className.equals(s.attributes().get("code.namespace")) && method.equals(s.attributes().get("code.function"))) {
                    occurrence++;
                }
                if (s == chosen) {
                    break;
                }
            }
            result.add(new ReplaySessions.Breakpoint(className, method, occurrence));
        }
        return result;
    }

    private static String simpleType(String type) {
        String raw = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        return raw.substring(raw.lastIndexOf('.') + 1);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "invalid JSON";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /**
     * @param breakpoints span IDs of the original trace to stop at
     */
    public record SessionRequest(String traceId, String spanId, String target, Boolean confirm, String body,
            List<String> breakpoints) {

        boolean confirmed() {
            return Boolean.TRUE.equals(confirm);
        }
    }

    /**
     * @param arguments the paused method's arguments by name, as JSON; omitted ones stay as they were
     * @param skipRest  run the rest of the replay without stopping again
     */
    public record ContinueRequest(Map<String, String> arguments, Boolean skipRest) {

        boolean skipRemaining() {
            return Boolean.TRUE.equals(skipRest);
        }
    }

    /** What the UI shows while a paused replay runs. Captured values are shown, never the method itself. */
    public record SessionView(String id, ReplaySessions.State state, String replayTraceId, PausedView paused,
            ReplayService.Outcome outcome, String error, List<String> edited) {

        static SessionView of(ReplaySessions.Session s) {
            ReplaySessions.Paused p = s.paused();
            return new SessionView(s.id(), s.state(), s.replayTraceId(),
                    p == null ? null : new PausedView(p.step(), p.occurrence(), p.arguments(), p.since().toString()),
                    s.outcome(), s.error(), s.edited());
        }
    }

    public record PausedView(String step, int occurrence, List<ReplaySessions.Argument> arguments, String since) {
    }

    @ExceptionHandler({ReplayException.class, CompletionException.class})
    ResponseEntity<Map<String, String>> refused(RuntimeException e) throws RuntimeException {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof ReplayException refused) {
            return ResponseEntity.status(refused.status()).body(Map.of("error", refused.getMessage()));
        }
        throw e;
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
