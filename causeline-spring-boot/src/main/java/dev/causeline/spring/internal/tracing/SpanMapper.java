// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.spring.autoconfigure.CauselineProperties.SqlCapture;
import dev.causeline.spring.internal.capture.RequestCaptureFilter;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts OpenTelemetry spans produced by Micrometer observations into Causeline spans.
 *
 * <p>Attributes are allowlisted: only the keys below survive, renamed to OpenTelemetry semantic
 * conventions where one exists. Anything else, including full URLs with query strings and SQL
 * text, is dropped.
 */
public final class SpanMapper {

    /** Attribute that Causeline's own observations use to state their span kind. */
    public static final String KIND_ATTRIBUTE = "causeline.kind";

    private static final Pattern SQL_OPERATION = Pattern.compile("^\\s*(select|insert|update|delete|merge|call)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_TABLE = Pattern.compile(
            "\\b(?:from|into|update|merge\\s+into)\\s+[\"`\\[]?([\\w.]+)", Pattern.CASE_INSENSITIVE);

    // String literals ('it''s'), then standalone numbers; identifiers such as t1 are left alone.
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern SQL_NUMBER_LITERAL = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)?\\b");
    private static final int MAX_SQL_CHARS = 2_000;

    private final String source;
    private final SqlCapture sql;

    public SpanMapper(String source) {
        this(source, SqlCapture.OPERATION);
    }

    public SpanMapper(String source, SqlCapture sql) {
        this.source = source;
        this.sql = sql;
    }

    /**
     * The statement with every literal replaced by {@code ?}. Prepared statements already use
     * {@code ?} for bind values, which are never recorded; this also covers inlined literals.
     */
    public static String sanitizeSql(String statement) {
        String withoutStrings = SQL_STRING_LITERAL.matcher(statement).replaceAll("?");
        String withoutNumbers = SQL_NUMBER_LITERAL.matcher(withoutStrings).replaceAll("?");
        return truncateSql(withoutNumbers);
    }

    /**
     * The statement with its bound values in place of the placeholders ({@code ?} for JDBC,
     * {@code $1}, {@code $2}... for R2DBC drivers), so it reads as it ran. {@code parameters} is datasource-proxy's display form, {@code (v1,v2,...)}, which does not
     * quote values; the values are only filled in when they match the placeholders one to one, so a
     * value containing a comma leaves the statement unchanged rather than showing a wrong query.
     * Quoting is inferred: numbers, booleans and null as-is, everything else as a string literal.
     */
    static String withValues(String statement, String parameters) {
        if (parameters == null || !parameters.startsWith("(") || !parameters.endsWith(")")
                || parameters.contains("),(")) {
            return statement; // absent, unexpected, or a batch: several value sets for one statement
        }
        String inner = parameters.substring(1, parameters.length() - 1);
        List<String> values = inner.isEmpty() ? List.of() : List.of(inner.split(",", -1));
        List<int[]> placeholders = placeholderPositions(statement);
        if (values.isEmpty() || placeholders.isEmpty()) {
            return statement;
        }
        boolean numbered = placeholders.getFirst()[2] > 0;
        int expected = numbered ? placeholders.stream().mapToInt(p -> p[2]).max().orElse(0) : placeholders.size();
        if (expected != values.size()) {
            return statement;
        }
        StringBuilder out = new StringBuilder(statement.length() + inner.length() + 8 * values.size());
        int from = 0;
        for (int i = 0; i < placeholders.size(); i++) {
            int[] placeholder = placeholders.get(i);
            // $n refers to the n-th value; a number with no value means the values don't match.
            int index = numbered ? placeholder[2] - 1 : i;
            if (index < 0 || index >= values.size()) {
                return statement;
            }
            out.append(statement, from, placeholder[0]).append(literal(values.get(index)));
            from = placeholder[1];
        }
        return out.append(statement.substring(from)).toString();
    }

    /**
     * Placeholders outside string literals and quoted identifiers, as {start, end, number}: number
     * is 0 for {@code ?} and n for {@code $n}. A statement mixing both styles is left alone.
     */
    private static List<int[]> placeholderPositions(String statement) {
        List<int[]> positions = new ArrayList<>();
        char quote = 0;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '?') {
                positions.add(new int[] {i, i + 1, 0});
            } else if (c == '$' && i + 1 < statement.length() && Character.isDigit(statement.charAt(i + 1))) {
                int end = i + 1;
                while (end < statement.length() && Character.isDigit(statement.charAt(end))) {
                    end++;
                }
                positions.add(new int[] {i, end, Integer.parseInt(statement.substring(i + 1, end))});
                i = end - 1;
            }
        }
        boolean mixed = positions.stream().map(p -> p[2] > 0).distinct().count() > 1;
        return mixed ? List.of() : positions;
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private static String literal(String value) {
        String v = value.trim();
        if (v.equals("null") || v.equals("true") || v.equals("false") || NUMBER.matcher(v).matches()) {
            return v.equals("null") ? "NULL" : v;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String truncateSql(String statement) {
        String compact = statement.replaceAll("\\s+", " ").trim();
        return compact.length() <= MAX_SQL_CHARS ? compact : compact.substring(0, MAX_SQL_CHARS) + "…";
    }

    public Span map(SpanData data) {
        Map<String, String> raw = new HashMap<>();
        data.getAttributes().forEach((key, value) -> raw.put(key.getKey(), String.valueOf(value)));

        SpanKind kind = kindOf(data, raw);
        Map<String, String> attributes = new LinkedHashMap<>();
        String name = switch (kind) {
            case REQUEST -> {
                copy(raw, "method", attributes, "http.request.method");
                copy(raw, "uri", attributes, "http.route");
                copy(raw, "status", attributes, "http.response.status_code");
                // Opt-in details, already filtered by RequestCaptureFilter.
                copy(raw, RequestCaptureFilter.QUERY, attributes, "url.query");
                copy(raw, RequestCaptureFilter.PATH, attributes, "url.path");
                copy(raw, RequestCaptureFilter.BODY, attributes, "http.request.body");
                copy(raw, RequestCaptureFilter.RESPONSE_BODY, attributes, "http.response.body");
                raw.forEach((key, value) -> {
                    if (key.startsWith(RequestCaptureFilter.HEADER)) {
                        attributes.put("http.request.header." + key.substring(RequestCaptureFilter.HEADER.length()), value);
                    }
                });
                yield join(raw.get("method"), raw.get("uri"), data.getName());
            }
            case HTTP_CLIENT -> {
                copy(raw, "method", attributes, "http.request.method");
                copy(raw, "client.name", attributes, "server.address");
                copy(raw, "uri", attributes, "url.template");
                copy(raw, "status", attributes, "http.response.status_code");
                String target = raw.containsKey("client.name") && raw.containsKey("uri")
                        ? raw.get("client.name") + raw.get("uri")
                        : raw.get("uri");
                yield join(raw.get("method"), target, data.getName());
            }
            case DATABASE -> {
                String dbName = databaseName(raw, attributes, data.getName());
                String prefix = raw.containsKey("jdbc.query[0]") ? "jdbc." : "r2dbc.";
                String statement = raw.get(prefix + "query[0]");
                if (statement != null && sql == SqlCapture.STATEMENT) {
                    attributes.put("db.query.text", sanitizeSql(statement));
                } else if (statement != null && sql == SqlCapture.FULL) {
                    String parameters = raw.get(prefix + "params[0]");
                    if (parameters != null && !parameters.isBlank()) {
                        attributes.put("db.query.parameters", parameters);
                        attributes.put("db.query.statement", truncateSql(statement));
                    }
                    attributes.put("db.query.text", truncateSql(withValues(statement, parameters)));
                }
                yield dbName;
            }
            case MESSAGE -> {
                // Kafka and RabbitMQ observations use OpenTelemetry's messaging conventions.
                copy(raw, "messaging.system", attributes, "messaging.system");
                copy(raw, "messaging.destination.name", attributes, "messaging.destination.name");
                copy(raw, "messaging.kafka.consumer.group", attributes, "messaging.consumer.group.name");
                copy(raw, "messaging.rabbitmq.destination.routing_key", attributes, "messaging.rabbitmq.destination.routing_key");
                String destination = raw.getOrDefault("messaging.destination.name", raw.get("spring.rabbit.listener.id"));
                String verb = data.getKind() == io.opentelemetry.api.trace.SpanKind.PRODUCER ? "send" : "receive";
                yield destination == null ? data.getName() : verb + " " + destination;
            }
            default -> {
                copy(raw, "class", attributes, "code.namespace");
                copy(raw, "method", attributes, "code.function");
                copy(raw, MethodValues.ARGUMENTS, attributes, MethodValues.ARGUMENTS);
                copy(raw, MethodValues.RETURNED, attributes, MethodValues.RETURNED);
                yield raw.containsKey("class") && raw.containsKey("method")
                        ? simpleName(raw.get("class")) + "." + raw.get("method")
                        : data.getName();
            }
        };
        copy(raw, "outcome", attributes, "outcome");
        copy(raw, "exception", attributes, "exception.type");
        copy(raw, "error", attributes, "exception.type");

        String parent = data.getParentSpanContext().isValid() ? data.getParentSpanId() : null;
        return new Span(
                data.getTraceId(),
                data.getSpanId(),
                parent,
                kind,
                name,
                source,
                data.getStartEpochNanos(),
                Math.max(0, data.getEndEpochNanos() - data.getStartEpochNanos()),
                statusOf(data, raw),
                attributes);
    }

    private static SpanKind kindOf(SpanData data, Map<String, String> raw) {
        String explicit = raw.get(KIND_ATTRIBUTE);
        if (explicit != null) {
            return SpanKind.valueOf(explicit);
        }
        if (raw.keySet().stream().anyMatch(k -> k.startsWith("jdbc.") || k.startsWith("r2dbc."))) {
            return SpanKind.DATABASE;
        }
        return switch (data.getKind()) {
            case SERVER -> SpanKind.REQUEST;
            case CLIENT -> SpanKind.HTTP_CLIENT;
            case PRODUCER, CONSUMER -> SpanKind.MESSAGE;
            default -> SpanKind.SERVICE;
        };
    }

    /**
     * Follows OpenTelemetry's HTTP conventions: a 4xx is the caller's mistake, so it fails a
     * client span but not the server span that correctly answered it.
     */
    private static SpanStatus statusOf(SpanData data, Map<String, String> raw) {
        String outcome = raw.get("outcome");
        if ("SERVER_ERROR".equals(outcome)) {
            return SpanStatus.ERROR;
        }
        if ("CLIENT_ERROR".equals(outcome)) {
            return data.getKind() == io.opentelemetry.api.trace.SpanKind.SERVER ? SpanStatus.OK : SpanStatus.ERROR;
        }
        return data.getStatus().getStatusCode() == StatusCode.ERROR ? SpanStatus.ERROR : SpanStatus.OK;
    }

    /** Names a query by operation and table only; the SQL text itself is never kept. */
    private static String databaseName(Map<String, String> raw, Map<String, String> attributes, String fallback) {
        String sql = raw.entrySet().stream()
                .filter(e -> e.getKey().startsWith("jdbc.query") || e.getKey().startsWith("r2dbc.query["))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        Matcher op = SQL_OPERATION.matcher(sql);
        Matcher table = SQL_TABLE.matcher(sql);
        String operation = op.find() ? op.group(1).toUpperCase(Locale.ROOT) : null;
        String collection = table.find() ? table.group(1) : null;
        if (operation != null) {
            attributes.put("db.operation.name", operation);
        }
        if (collection != null) {
            attributes.put("db.collection.name", collection);
        }
        copy(raw, "jdbc.datasource.name", attributes, "db.namespace");
        return operation == null ? fallback : join(operation, collection, operation);
    }

    private static void copy(Map<String, String> from, String key, Map<String, String> to, String as) {
        String value = from.get(key);
        // Micrometer writes "none" when a tag has no value, e.g. exception=none on success.
        if (value != null && !value.isBlank() && !"none".equals(value)) {
            to.put(as, value);
        }
    }

    private static String join(String first, String second, String fallback) {
        if (first == null || second == null) {
            return fallback;
        }
        return first.toUpperCase(Locale.ROOT) + " " + second;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
