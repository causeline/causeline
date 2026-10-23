// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.explain;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.spring.internal.export.UpstreamForwarder;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Runs {@code EXPLAIN} for a captured SELECT against the application's own database, when someone
 * asks for it in the UI. This is the only place Causeline sends SQL to a database, so it is
 * deliberately narrow:
 *
 * <ul>
 *   <li>Only a query this application recorded itself: not one forwarded by another service or
 *       loaded from a file, whose SQL someone else wrote.</li>
 *   <li>Only one SELECT (or WITH) statement, never {@code EXPLAIN ANALYZE}: the query is planned,
 *       not run.</li>
 *   <li>The statement is the one the application prepared, and captured values are bound as
 *       parameters. The filled-in text shown in the UI is never executed: a value containing a
 *       quote would become SQL.</li>
 *   <li>On a read-only connection, in a transaction that is rolled back, with a time limit.</li>
 * </ul>
 */
public final class QueryExplainer {

    /** A refusal, or the database's own error, worded for the person who clicked. */
    public static final class ExplainException extends RuntimeException {
        ExplainException(String message) {
            super(message);
        }
    }

    /**
     * @param database the database product and version
     * @param plan     the plan as the database printed it
     * @param findings what in it deserves attention; empty when nothing does
     */
    public record Plan(String database, List<String> plan, List<String> findings) {
    }

    private static final int MAX_ROWS = 500;

    private final Supplier<DataSource> dataSource;
    private final String source;
    private final Duration timeout;

    /**
     * @param dataSource the application's JDBC data source; supplies null when it has none
     * @param source     this application's name on the spans it records
     */
    public QueryExplainer(Supplier<DataSource> dataSource, String source, Duration timeout) {
        this.dataSource = dataSource;
        this.source = source;
        this.timeout = timeout;
    }

    public Plan explain(Span span) {
        String sql = statementOf(span);
        List<Object> values = values(span.attributes().get("db.query.parameters"), placeholders(sql));
        DataSource target = dataSource.get();
        if (target == null) {
            throw new ExplainException("EXPLAIN needs a JDBC DataSource, and this application has none."
                    + " R2DBC connections are not supported yet.");
        }
        try (Connection connection = target.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String database = product.toLowerCase(Locale.ROOT);
            if (!(database.contains("h2") || database.contains("postgres") || database.contains("mysql")
                    || database.contains("mariadb"))) {
                throw new ExplainException("EXPLAIN is supported for H2, PostgreSQL, MySQL and MariaDB; this is " + product + ".");
            }
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            try {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                return plan(connection, product, sql, values, database.contains("postgres"));
            } finally {
                restore(connection, autoCommit, readOnly);
            }
        } catch (SQLException e) {
            throw new ExplainException("The database could not explain this query: " + e.getMessage());
        }
    }

    private Plan plan(Connection connection, String product, String sql, List<Object> values, boolean postgres)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN " + sql)) {
            statement.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
            statement.setMaxRows(MAX_ROWS);
            for (int i = 0; i < values.size(); i++) {
                bind(statement, i + 1, values.get(i), postgres);
            }
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int c = 1; c <= meta.getColumnCount(); c++) {
                    columns.add(meta.getColumnLabel(c));
                }
                List<List<String>> rows = new ArrayList<>();
                while (result.next()) {
                    List<String> row = new ArrayList<>();
                    for (int c = 1; c <= columns.size(); c++) {
                        row.add(result.getString(c));
                    }
                    rows.add(row);
                }
                return new Plan(product + " " + connection.getMetaData().getDatabaseProductVersion(),
                        QueryPlans.lines(columns, rows), QueryPlans.findings(product, columns, rows));
            }
        }
    }

    private static void bind(PreparedStatement statement, int index, Object value, boolean postgres) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else if (value instanceof String text && postgres) {
            // The captured values carry no types. Sent as "unknown", PostgreSQL infers the type from
            // the column it is compared with, as it would for a literal.
            statement.setObject(index, text, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }

    private static void restore(Connection connection, boolean autoCommit, boolean readOnly) {
        try {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
            connection.setReadOnly(readOnly);
        } catch (SQLException e) {
            // The connection goes back to the pool, which resets or discards it.
        }
    }

    /** The statement to plan, after every check that doesn't need the database. */
    private String statementOf(Span span) {
        if (span.kind() != SpanKind.DATABASE) {
            throw new ExplainException("Only SQL queries can be explained.");
        }
        if (!source.equals(span.source()) || "true".equals(span.attributes().get(UpstreamForwarder.FORWARDED_ATTRIBUTE))) {
            throw new ExplainException("This query ran in " + span.source() + ", not in this application."
                    + " Open that service's Causeline to explain it.");
        }
        String sql = span.attributes().getOrDefault("db.query.statement", span.attributes().get("db.query.text"));
        if (sql == null || sql.isBlank()) {
            throw new ExplainException("The SQL text of this query wasn't captured (causeline.capture.sql).");
        }
        if (sql.endsWith("…")) {
            throw new ExplainException("This statement was too long to capture in full, so it can't be explained.");
        }
        sql = sql.strip();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        String start = sql.toUpperCase(Locale.ROOT);
        if (!(start.startsWith("SELECT") || start.startsWith("WITH"))) {
            throw new ExplainException("Only SELECT statements are explained.");
        }
        if (sql.indexOf(';') >= 0) {
            throw new ExplainException("This is more than one statement; only a single SELECT is explained.");
        }
        return sql;
    }

    /** {@code ?} placeholders outside string literals and quoted identifiers. */
    static int placeholders(String sql) {
        int count = 0;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '?') {
                count++;
            }
        }
        return count;
    }

    /**
     * The captured bind values, typed as far as their text allows. {@code parameters} is
     * datasource-proxy's display form, {@code (v1,v2,...)}, which doesn't quote values: when the
     * count doesn't match the placeholders (a value with a comma, a batch), there is no telling
     * which value goes where, so nothing is run.
     */
    static List<Object> values(String parameters, int placeholders) {
        if (placeholders == 0) {
            return List.of();
        }
        if (parameters == null) {
            throw new ExplainException("This query has bind values that weren't captured. Set causeline.capture.sql=full"
                    + " and run the request again.");
        }
        boolean plain = parameters.startsWith("(") && parameters.endsWith(")") && !parameters.contains("),(");
        String[] texts = plain ? parameters.substring(1, parameters.length() - 1).split(",", -1) : new String[0];
        if (texts.length != placeholders) {
            throw new ExplainException("The captured values can't be matched to this query's " + placeholders
                    + " placeholders (a batch, or a value containing a comma), so it can't be explained.");
        }
        List<Object> values = new ArrayList<>();
        for (String text : texts) {
            values.add(typed(text));
        }
        return values;
    }

    private static Object typed(String text) {
        if (text.equals("null")) {
            return null;
        }
        if (text.equals("true") || text.equals("false")) {
            return Boolean.valueOf(text);
        }
        if (text.matches("-?\\d{1,18}")) {
            return Long.valueOf(text);
        }
        if (text.matches("-?\\d+\\.\\d+")) {
            return new BigDecimal(text);
        }
        return text;
    }
}
