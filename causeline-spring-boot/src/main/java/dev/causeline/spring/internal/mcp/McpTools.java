// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.mcp;

import dev.causeline.core.Baselines;
import dev.causeline.core.Insight;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceComparison;
import dev.causeline.core.TraceStore;
import dev.causeline.core.TraceSummary;
import dev.causeline.core.TraceView;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.replay.ReplayRecord;
import dev.causeline.spring.internal.replay.ReplayService;
import dev.causeline.spring.internal.web.SourceLocator;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;

/**
 * The tools a coding agent can call over MCP. Every attribute value they return comes from
 * {@link SpanRedactor}, like an export: an agent's context usually goes to a model provider.
 * Structure, self time and insights come from the trace as captured; their labels name spans,
 * never captured values.
 */
public final class McpTools {

    /** Longer values are cut, so one large body can't fill the agent's context. */
    static final int MAX_VALUE_CHARS = 2_000;
    static final int MAX_SPANS = 400;
    static final int DEFAULT_TRACES = 20;
    static final int MAX_TRACES = 100;
    private static final Duration REPLAY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * A tool as MCP lists it.
     *
     * @param readOnly    the tool changes nothing (MCP's {@code readOnlyHint})
     */
    public record Tool(String name, String description, Map<String, Object> inputSchema, boolean readOnly,
            Function<JsonNode, Object> call) {
    }

    /** A refusal or a missing trace: shown to the agent as a tool error, not a protocol error. */
    public static final class ToolError extends RuntimeException {
        public ToolError(String message) {
            super(message);
        }
    }

    private final TraceStore store;
    private final Function<String, Optional<String>> replayOf;
    private final SpanRedactor redactor;
    private final McpRules rules;
    private final ReplayService replays;
    private final SourceLocator sources;
    private final int maxReplaysPerMinute;
    private final LongSupplier clockMillis;
    private final Duration replayTraceWait;
    private final AgentReplays agentReplays;
    private final Deque<Long> recentReplays = new ArrayDeque<>();
    private final List<Tool> tools;

    /**
     * @param replays          null when replay isn't available (no Jackson on the classpath)
     * @param replayTraceWait  how long a local replay waits for its own trace, to compare it
     * @param agentReplays     receives each replay's trace ID before it is sent, for the UI's label
     */
    public McpTools(TraceStore store, Function<String, Optional<String>> replayOf, SpanRedactor redactor,
            McpRules rules, ReplayService replays, SourceLocator sources, int maxReplaysPerMinute,
            LongSupplier clockMillis, Duration replayTraceWait, AgentReplays agentReplays) {
        this.store = store;
        this.replayOf = replayOf;
        this.redactor = redactor;
        this.rules = rules;
        this.replays = replays;
        this.sources = sources;
        this.maxReplaysPerMinute = maxReplaysPerMinute;
        this.clockMillis = clockMillis;
        this.replayTraceWait = replayTraceWait;
        this.agentReplays = agentReplays;
        this.tools = List.of(
                new Tool("list_traces", "Recent traces recorded by Causeline in the running application, newest first: "
                        + "one per user action or request, with status and duration. Use it to find the trace of a "
                        + "failing or slow request.",
                        schema(Map.of(
                                "limit", prop("integer", "How many traces to return (default " + DEFAULT_TRACES + ", at most " + MAX_TRACES + ")"),
                                "name", prop("string", "Only traces whose name contains this text, e.g. a path such as /api/checkout"),
                                "failedOnly", prop("boolean", "Only traces that ended with an error")), List.of()),
                        true, this::listTraces),
                new Tool("get_trace", "One trace as a span tree: browser action, HTTP requests, controller, service, "
                        + "repository and SQL spans, exceptions, logs and React state updates, with inclusive and self "
                        + "time, captured values (secrets redacted) and insights. Request spans marked replayable can be "
                        + "passed to replay_request.",
                        schema(Map.of(
                                "traceId", prop("string", "32-hex trace ID from list_traces"),
                                "attributes", prop("boolean", "Include span attributes such as SQL, bodies and arguments (default true)")),
                                List.of("traceId")),
                        true, this::getTrace),
                new Tool("get_insights", "What Causeline found in one trace: the bottleneck by self time, N+1 and "
                        + "repeated queries, handled exceptions, transaction and frontend problems, and security hints.",
                        schema(Map.of("traceId", prop("string", "32-hex trace ID")), List.of("traceId")),
                        true, this::getInsights),
                new Tool("security_findings", "Passive security hints (OWASP Top 10:2025) on captured traffic: for one "
                        + "trace, or grouped by rule across every stored trace when traceId is omitted. They are hints, "
                        + "not proof.",
                        schema(Map.of("traceId", prop("string", "32-hex trace ID; omit for all traces")), List.of()),
                        true, this::securityFindings),
                new Tool("compare_traces", "Two traces compared span by span (matched by kind, name and position): "
                        + "which spans got faster or slower, changed status, or exist on one side only.",
                        schema(Map.of(
                                "baseline", prop("string", "Trace ID of the run to compare against"),
                                "traceId", prop("string", "Trace ID of the run being explained"),
                                "onlyChanges", prop("boolean", "Leave out unchanged spans (default true)")),
                                List.of("baseline", "traceId")),
                        true, this::compareTraces),
                new Tool("source_location", "Where an application class's source file is, relative to the project, "
                        + "e.g. for the code.namespace and code.lineno of a span or an exception's location.",
                        schema(Map.of(
                                "className", prop("string", "Fully qualified class name"),
                                "method", prop("string", "Method name, to find its line when no line is known"),
                                "line", prop("integer", "Line number, if known")), List.of("className")),
                        true, this::sourceLocation),
                new Tool("replay_request", "Sends a captured request again, e.g. to check a fix, and returns the new "
                        + "HTTP status and trace ID; for the local target, also the comparison with the original. "
                        + "Only requests the developer allowed can be replayed (causeline.mcp.replay.*). Requests "
                        + "with side effects (POST, PUT, PATCH, DELETE) repeat them and need confirm: true. Headers, "
                        + "including credentials, are resent as captured and can't be changed.",
                        schema(Map.of(
                                "traceId", prop("string", "Trace ID of the original request"),
                                "spanId", prop("string", "The request span to replay; may be omitted when the trace has one replayable request"),
                                "target", prop("string", "Replay target (default local, this application)"),
                                "confirm", prop("boolean", "Required for requests with side effects"),
                                "path", prop("string", "Send to this path instead of the captured one"),
                                "query", prop("string", "Send this query string instead of the captured one; empty for none"),
                                "body", prop("string", "Send this body instead of the captured one")),
                                List.of("traceId")),
                        false, this::replayRequest));
    }

    public List<Tool> tools() {
        return tools;
    }

    // --- read tools ---------------------------------------------------------------------------

    private Object listTraces(JsonNode args) {
        int limit = Math.clamp(intArg(args, "limit", DEFAULT_TRACES), 1, MAX_TRACES);
        String name = stringArg(args, "name");
        boolean failedOnly = boolArg(args, "failedOnly", false);
        List<TraceSummary> summaries = store.snapshot().stream()
                .filter(spans -> !isHidden(spans))
                .map(spans -> TraceAssembler.assemble(spans.getFirst().traceId(), spans).summary())
                .map(summary -> summary.withReplayOf(replayOf.apply(summary.traceId()).orElse(null)))
                .toList();
        List<Map<String, Object>> traces = Baselines.apply(summaries).stream()
                .filter(s -> name == null || s.name().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)))
                .filter(s -> !failedOnly || s.status() == SpanStatus.ERROR)
                .sorted(Comparator.comparingLong(TraceSummary::startTimeUnixNano).reversed())
                .limit(limit)
                .map(s -> {
                    Map<String, Object> trace = new LinkedHashMap<>();
                    trace.put("traceId", s.traceId());
                    trace.put("name", s.name());
                    trace.put("status", s.status());
                    trace.put("startedAt", Instant.ofEpochSecond(0, s.startTimeUnixNano()).toString());
                    trace.put("durationMs", ms(s.durationNanos()));
                    trace.put("spans", s.spanCount());
                    if (s.replayOf() != null) {
                        trace.put("replayOf", s.replayOf());
                    }
                    if (s.baselineNanos() != null) {
                        trace.put("usualDurationMs", ms(s.baselineNanos()));
                    }
                    return trace;
                })
                .toList();
        return Map.of("traces", traces);
    }

    private Object getTrace(JsonNode args) {
        String traceId = requiredArg(args, "traceId");
        List<Span> spans = visible(traceId);
        boolean withAttributes = boolArg(args, "attributes", true);
        TraceView view = TraceAssembler.assemble(traceId, spans);
        Map<String, Map<String, String>> redacted = new LinkedHashMap<>();
        spans.forEach(span -> redacted.put(span.spanId(), redactor.redact(span).attributes()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TraceView.Row row : view.spans()) {
            if (rows.size() >= MAX_SPANS) {
                break;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("spanId", row.spanId());
            if (row.parentSpanId() != null) {
                out.put("parentSpanId", row.parentSpanId());
            }
            out.put("depth", row.depth());
            out.put("kind", row.kind());
            out.put("name", row.name());
            out.put("source", row.source());
            out.put("status", row.status());
            out.put("startMs", ms(row.offsetNanos()));
            out.put("durationMs", ms(row.durationNanos()));
            out.put("selfMs", ms(row.selfNanos()));
            if (replays != null && row.kind() == SpanKind.REQUEST && replays.record(traceId, row.spanId()).isPresent()) {
                out.put("replayable", true);
            }
            if (withAttributes) {
                out.put("attributes", truncated(redacted.getOrDefault(row.spanId(), Map.of())));
            }
            rows.add(out);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("name", view.name());
        result.put("status", view.status());
        result.put("durationMs", ms(view.durationNanos()));
        result.put("insights", insights(view, false));
        result.put("spans", rows);
        if (view.spans().size() > MAX_SPANS) {
            result.put("note", "Only the first " + MAX_SPANS + " of " + view.spans().size() + " spans are shown.");
        }
        return result;
    }

    private Object getInsights(JsonNode args) {
        String traceId = requiredArg(args, "traceId");
        return Map.of("insights", insights(TraceAssembler.assemble(traceId, visible(traceId)), false));
    }

    private Object securityFindings(JsonNode args) {
        String traceId = stringArg(args, "traceId");
        if (traceId != null) {
            return Map.of("findings", insights(TraceAssembler.assemble(traceId, visible(traceId)), true));
        }
        Map<String, List<String[]>> byRule = new LinkedHashMap<>();
        for (List<Span> spans : store.snapshot()) {
            if (isHidden(spans)) {
                continue;
            }
            TraceView view = TraceAssembler.assemble(spans.getFirst().traceId(), spans);
            view.insights().stream().filter(i -> i.rule().isSecurity())
                    .forEach(i -> byRule.computeIfAbsent(i.rule().name(), k -> new ArrayList<>())
                            .add(new String[] {view.traceId(), view.name(), i.label()}));
        }
        List<Map<String, Object>> rulesFound = new ArrayList<>();
        byRule.forEach((rule, findings) -> {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("rule", rule);
            group.put("traces", findings.size());
            group.put("endpoints", findings.stream().map(f -> f[1]).distinct().toList());
            group.put("example", findings.getFirst()[2]);
            group.put("exampleTraceId", findings.getFirst()[0]);
            rulesFound.add(group);
        });
        rulesFound.sort(Comparator.comparingInt((Map<String, Object> g) -> (int) g.get("traces")).reversed());
        return Map.of("rules", rulesFound);
    }

    private Object compareTraces(JsonNode args) {
        String baseline = requiredArg(args, "baseline");
        String traceId = requiredArg(args, "traceId");
        TraceComparison.Result result = TraceComparison.compareTraces(
                TraceAssembler.assemble(baseline, visible(baseline)), TraceAssembler.assemble(traceId, visible(traceId)));
        return comparison(result, boolArg(args, "onlyChanges", true));
    }

    private Object sourceLocation(JsonNode args) {
        String className = requiredArg(args, "className");
        return sources.locate(className, stringArg(args, "method"), intArg(args, "line", 0))
                .orElseThrow(() -> new ToolError("No source file found for " + className
                        + " under src/main or src/test of the working directory."));
    }

    // --- replay -------------------------------------------------------------------------------

    private Object replayRequest(JsonNode args) {
        if (replays == null) {
            throw new ToolError("Replay is not available in this application.");
        }
        String traceId = requiredArg(args, "traceId");
        List<Span> spans = visible(traceId);
        String spanId = stringArg(args, "spanId");
        if (spanId == null) {
            spanId = onlyReplayable(traceId, spans);
        }
        String requestSpanId = spanId;
        ReplayRecord record = replays.record(traceId, spanId).orElseThrow(() -> new ToolError(
                "Nothing captured to replay span " + requestSpanId + " of this trace. Use a request span that "
                        + "get_trace marks replayable."));
        String target = Optional.ofNullable(stringArg(args, "target")).orElse(ReplayService.LOCAL_TARGET);
        if (!replays.targets().containsKey(target)) {
            throw new ToolError("Unknown replay target '" + target + "'. Known targets: "
                    + String.join(", ", replays.targets().keySet()) + ".");
        }
        if (!replays.allowsMcp(target)) {
            throw new ToolError("Target '" + target + "' doesn't accept replays from agents. The developer can set "
                    + "causeline.replay.targets." + target + ".allow-mcp=true.");
        }
        String path = stringArg(args, "path");
        String refusal = rules.replayRefusal(record.method(), path != null ? path : record.path());
        if (refusal != null) {
            throw new ToolError(refusal);
        }
        acquireReplay();
        ReplayService.Outcome outcome;
        try {
            outcome = replays.replay(traceId, spanId, target, boolArg(args, "confirm", false), stringArg(args, "body"),
                    new ReplayService.Edits(path, stringArg(args, "query"), null), Map.of(), REPLAY_TIMEOUT,
                    agentReplays::add);
        } catch (ReplayService.ReplayException e) {
            throw new ToolError(e.status() == 409 ? e.getMessage().replace("Confirm to continue.",
                    "Call again with confirm: true if that is intended.") : e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replayTraceId", outcome.replayTraceId());
        result.put("target", outcome.target());
        result.put("httpStatus", outcome.httpStatus());
        result.put("durationMs", ms(outcome.durationNanos()));
        if (outcome.sentRedactedFields()) {
            result.put("note", "Some body fields were captured as [REDACTED] and were sent that way.");
        }
        if (replays.isLocal(target)) {
            Optional<List<Span>> replay = awaitReplayTrace(outcome.replayTraceId());
            if (replay.isPresent()) {
                result.put("comparison", comparison(TraceComparison.compare(TraceAssembler.assemble(traceId, spans),
                        spanId, TraceAssembler.assemble(outcome.replayTraceId(), replay.get())), true));
            } else {
                result.put("comparison", "The replay's trace hasn't arrived yet; call compare_traces with "
                        + traceId + " and " + outcome.replayTraceId() + " shortly.");
            }
        }
        return result;
    }

    private String onlyReplayable(String traceId, List<Span> spans) {
        List<Span> replayable = spans.stream()
                .filter(s -> s.kind() == SpanKind.REQUEST && replays.record(traceId, s.spanId()).isPresent())
                .toList();
        if (replayable.size() == 1) {
            return replayable.getFirst().spanId();
        }
        if (replayable.isEmpty()) {
            throw new ToolError("This trace has no replayable request.");
        }
        throw new ToolError("This trace has " + replayable.size() + " replayable requests; pass spanId, one of: "
                + String.join(", ", replayable.stream().map(s -> s.spanId() + " (" + s.name() + ")").toList()) + ".");
    }

    private synchronized void acquireReplay() {
        long now = clockMillis.getAsLong();
        while (!recentReplays.isEmpty() && now - recentReplays.peekFirst() >= 60_000) {
            recentReplays.pollFirst();
        }
        if (recentReplays.size() >= maxReplaysPerMinute) {
            throw new ToolError("Agents may replay at most " + maxReplaysPerMinute + " requests per minute "
                    + "(causeline.mcp.replay.max-per-minute). Wait before trying again.");
        }
        recentReplays.addLast(now);
    }

    /** Spans arrive just after the response; waits until the replay's server request span is stored. */
    private Optional<List<Span>> awaitReplayTrace(String replayTraceId) {
        long deadline = System.nanoTime() + replayTraceWait.toNanos();
        do {
            Optional<List<Span>> spans = store.get(replayTraceId);
            if (spans.isPresent() && spans.get().stream().anyMatch(s -> s.kind() == SpanKind.REQUEST)) {
                return spans;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        } while (System.nanoTime() < deadline);
        return Optional.empty();
    }

    // --- helpers ------------------------------------------------------------------------------

    /** The trace's spans, unless it is missing or hidden; both answer the same, so hiding reveals nothing. */
    private List<Span> visible(String traceId) {
        return store.get(traceId).filter(spans -> !isHidden(spans))
                .orElseThrow(() -> new ToolError("No trace " + traceId + ". Use list_traces to find one."));
    }

    private boolean isHidden(List<Span> spans) {
        return spans.stream().filter(s -> s.kind() == SpanKind.REQUEST).map(McpTools::pathOf)
                .anyMatch(rules::isHidden);
    }

    private static String pathOf(Span span) {
        Map<String, String> attributes = span.attributes();
        String path = attributes.get("url.path");
        if (path != null) {
            return path;
        }
        String full = attributes.get("url.full");
        if (full != null) {
            try {
                return URI.create(full).getPath();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return attributes.get("http.route");
    }

    private static List<Map<String, Object>> insights(TraceView view, boolean securityOnly) {
        Map<String, String> names = new LinkedHashMap<>();
        view.spans().forEach(r -> names.put(r.spanId(), r.name()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Insight insight : view.insights()) {
            if (securityOnly && !insight.rule().isSecurity()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule", insight.rule());
            item.put("label", insight.label());
            item.put("spanId", insight.spanId());
            item.put("spanName", names.get(insight.spanId()));
            if (insight.sharePercent() > 0) {
                item.put("sharePercent", insight.sharePercent());
            }
            if (insight.rule().isSecurity()) {
                item.put("security", true);
            }
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> comparison(TraceComparison.Result result, boolean onlyChanges) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", side(result.original()));
        out.put("compared", side(result.replay()));
        out.put("spans", result.rows().stream()
                .filter(r -> !onlyChanges || r.change() != TraceComparison.Change.SAME)
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("depth", r.depth());
                    row.put("kind", r.kind());
                    row.put("name", r.name());
                    row.put("change", r.change());
                    row.put("baselineMs", r.originalNanos() == null ? null : ms(r.originalNanos()));
                    row.put("comparedMs", r.replayNanos() == null ? null : ms(r.replayNanos()));
                    if (r.originalStatus() != r.replayStatus()) {
                        row.put("baselineStatus", r.originalStatus());
                        row.put("comparedStatus", r.replayStatus());
                    }
                    return row;
                })
                .toList());
        return out;
    }

    private static Map<String, Object> side(TraceComparison.Side side) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", side.traceId());
        out.put("status", side.status());
        out.put("durationMs", ms(side.durationNanos()));
        out.put("httpStatus", side.httpStatus());
        return out;
    }

    private static Map<String, String> truncated(Map<String, String> attributes) {
        Map<String, String> out = new java.util.TreeMap<>();
        attributes.forEach((key, value) -> out.put(key, value == null || value.length() <= MAX_VALUE_CHARS ? value
                : value.substring(0, MAX_VALUE_CHARS) + "… [" + (value.length() - MAX_VALUE_CHARS) + " more characters]"));
        return out;
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new java.util.TreeMap<>(properties));
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static String stringArg(JsonNode args, String name) {
        JsonNode value = args.path(name);
        return value.isString() ? value.asString() : null;
    }

    private static String requiredArg(JsonNode args, String name) {
        String value = stringArg(args, name);
        if (value == null || value.isBlank()) {
            throw new ToolError("Missing argument '" + name + "'.");
        }
        return value.strip();
    }

    private static boolean boolArg(JsonNode args, String name, boolean fallback) {
        JsonNode value = args.path(name);
        return value.isBoolean() ? value.booleanValue() : fallback;
    }

    private static int intArg(JsonNode args, String name, int fallback) {
        JsonNode value = args.path(name);
        return value.isNumber() ? value.asInt() : fallback;
    }
}
