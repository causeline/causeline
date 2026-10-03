// SPDX-License-Identifier: Apache-2.0
package dev.causeline.test;

import dev.causeline.core.Insight;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceView;
import dev.causeline.spring.internal.tracing.SpanMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Assertions on one recorded trace. Every check is about what the application did, not how long
 * it took, so the same test gives the same answer on any machine. A failure message ends with the
 * whole trace: every span, each query's SQL and where each exception was thrown.
 *
 * <p>Failure messages end up in build logs, so they carry no captured values: SQL is printed with
 * its literals and bind values as {@code ?}, and exception messages, arguments and bodies are left out.
 */
public final class TraceAssert {

    private static final int MAX_SQL_CHARS = 300;

    private final TraceView trace;

    TraceAssert(List<Span> spans) {
        this.trace = TraceAssembler.assemble(spans.getFirst().traceId(), spans);
    }

    /** The trace's name: its first root span, for example {@code POST /api/orders}. */
    public String name() {
        return trace.name();
    }

    /**
     * No span failed and no exception was thrown, including exceptions the application caught and
     * logged. Following OpenTelemetry, a 4xx answer does not fail the server's own request span.
     */
    public TraceAssert hasNoFailedSpans() {
        List<String> failed = trace.spans().stream()
                .filter(row -> row.status() == SpanStatus.ERROR || row.kind() == SpanKind.EXCEPTION)
                .map(TraceAssert::line)
                .toList();
        if (!failed.isEmpty()) {
            throw failure("Expected no failed spans but found " + failed.size() + ":", failed);
        }
        return this;
    }

    /** An exception with this simple class name was thrown or logged, for example {@code "PaymentTimeoutException"}. */
    public TraceAssert hasException(String simpleClassName) {
        List<String> exceptions = names(SpanKind.EXCEPTION);
        if (!exceptions.contains(simpleClassName)) {
            throw failure("Expected exception " + simpleClassName + " but the trace had "
                    + (exceptions.isEmpty() ? "none" : exceptions.toString()) + ".", List.of());
        }
        return this;
    }

    /** Exactly this many SQL statements ran. */
    public TraceAssert hasQueryCount(int expected) {
        List<String> queries = queries();
        if (queries.size() != expected) {
            throw failure("Expected " + count(expected) + " but " + queries.size() + " ran:", queries);
        }
        return this;
    }

    /** No more than this many SQL statements ran. Fewer is fine, for example when a cache was warm. */
    public TraceAssert hasQueryCountAtMost(int limit) {
        List<String> queries = queries();
        if (queries.size() > limit) {
            throw failure("Expected at most " + count(limit) + " but " + queries.size() + " ran:", queries);
        }
        return this;
    }

    /**
     * No query ran again and again under one parent: the N+1 pattern of lazy loading. Uses the rule
     * the Causeline UI flags (five or more identical SELECTs, or ten or more of another statement).
     */
    public TraceAssert hasNoRepeatedQueries() {
        List<String> repeated = trace.insights().stream()
                .filter(insight -> insight.rule() == Insight.Rule.REPEATED_QUERY)
                .map(Insight::label)
                .toList();
        if (!repeated.isEmpty()) {
            throw failure("Expected no repeated queries but found:", repeated);
        }
        return this;
    }

    /** A span with this name exists, for example {@code "OrderService.createOrder"}. */
    public TraceAssert hasSpan(String name) {
        return hasSpans(name);
    }

    /** Every one of these spans exists, in any order. */
    public TraceAssert hasSpans(String... names) {
        List<String> missing = Arrays.stream(names).filter(name -> !contains(name)).toList();
        if (!missing.isEmpty()) {
            throw failure("Expected spans that are not in the trace:", missing);
        }
        return this;
    }

    /**
     * These spans exist in this order, reading the trace top to bottom (a parent before its
     * children, siblings by start time). Other spans may sit in between.
     */
    public TraceAssert hasSpansInOrder(String... names) {
        int next = 0;
        for (TraceView.Row row : trace.spans()) {
            if (next < names.length && row.name().equals(names[next])) {
                next++;
            }
        }
        if (next < names.length) {
            throw failure("Expected spans in the order " + Arrays.toString(names) + " but \"" + names[next]
                    + "\" was not found after the ones before it.", List.of());
        }
        return this;
    }

    /** No span has this name. */
    public TraceAssert hasNoSpan(String name) {
        if (contains(name)) {
            throw failure("Expected no span named \"" + name + "\" but the trace has one.", List.of());
        }
        return this;
    }

    /** The trace as an indented tree, as printed in failure messages. */
    @Override
    public String toString() {
        StringBuilder tree = new StringBuilder();
        for (TraceView.Row row : trace.spans()) {
            tree.append("  ".repeat(row.depth() + 1)).append(line(row)).append('\n');
        }
        return tree.toString();
    }

    boolean contains(String spanName) {
        return trace.spans().stream().anyMatch(row -> row.name().equals(spanName));
    }

    private List<String> names(SpanKind kind) {
        return trace.spans().stream().filter(row -> row.kind() == kind).map(TraceView.Row::name).toList();
    }

    /** Each query as its name and SQL, without the kind: the heading already says they are queries. */
    private List<String> queries() {
        return trace.spans().stream()
                .filter(row -> row.kind() == SpanKind.DATABASE)
                .map(row -> row.name() + detail(row))
                .toList();
    }

    private static String count(int queries) {
        return queries == 1 ? "1 SQL query" : queries + " SQL queries";
    }

    private static String line(TraceView.Row row) {
        return row.kind() + " " + row.name() + (row.status() == SpanStatus.ERROR ? " [ERROR]" : "") + detail(row);
    }

    /** What makes a span recognisable beyond its name, with nothing in it that was captured from a request. */
    private static String detail(TraceView.Row row) {
        if (row.kind() == SpanKind.DATABASE) {
            String sql = row.attributes().getOrDefault("db.query.statement", row.attributes().get("db.query.text"));
            if (sql == null) {
                return "";
            }
            String shown = SpanMapper.sanitizeSql(sql).replaceAll("\\s+", " ").strip();
            return ": " + (shown.length() > MAX_SQL_CHARS ? shown.substring(0, MAX_SQL_CHARS) + "…" : shown);
        }
        if (row.kind() == SpanKind.EXCEPTION && row.attributes().containsKey("code.location")) {
            return " at " + row.attributes().get("code.location");
        }
        return "";
    }

    private AssertionError failure(String problem, List<String> details) {
        List<String> lines = new ArrayList<>();
        lines.add(problem);
        details.forEach(detail -> lines.add("  " + detail));
        lines.add("Trace \"" + trace.name() + "\":");
        return new AssertionError(String.join("\n", lines) + "\n" + this);
    }
}
