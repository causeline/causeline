// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.explain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of {@code EXPLAIN} for the things worth pointing out: full table scans, and
 * sorting or grouping without an index. Rule-based, per database, on the plan as text or rows.
 */
public final class QueryPlans {

    /** H2 marks a scan in a comment after the table: {@code /* PUBLIC.ORDERS.tableScan *}{@code /}. */
    private static final Pattern H2_SCAN = Pattern.compile("/\\*\\s*([\\w.\"]+)\\.tableScan\\b");
    private static final Pattern POSTGRES_SCAN = Pattern.compile("Seq Scan on (\\S+)");

    private static final String ADVICE = ": the database reads every row to answer this query. If the table is large"
            + " in real use, add an index on the columns it filters or joins on";

    private QueryPlans() {
    }

    /**
     * @param product the JDBC database product name, e.g. {@code H2}, {@code PostgreSQL}, {@code MySQL}
     * @param columns the column labels of the EXPLAIN result
     * @param rows    its rows, as text
     */
    public static List<String> findings(String product, List<String> columns, List<List<String>> rows) {
        String database = product.toLowerCase(Locale.ROOT);
        if (database.contains("mysql") || database.contains("mariadb")) {
            return mysql(columns, rows);
        }
        List<String> lines = lines(columns, rows);
        return database.contains("postgres") ? postgres(lines) : h2(lines);
    }

    /** The plan as lines of text: as the database printed it, or one {@code column=value} line per row. */
    public static List<String> lines(List<String> columns, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        for (List<String> row : rows) {
            if (columns.size() == 1) {
                lines.addAll(List.of(String.valueOf(row.getFirst()).split("\\R")));
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                if (row.get(i) != null) {
                    cells.add(columns.get(i) + "=" + row.get(i));
                }
            }
            lines.add(String.join("  ", cells));
        }
        return lines;
    }

    private static List<String> h2(List<String> lines) {
        Set<String> findings = new LinkedHashSet<>();
        Matcher scan = H2_SCAN.matcher(String.join("\n", lines));
        while (scan.find()) {
            findings.add("Full table scan on " + table(scan.group(1)) + ADVICE);
        }
        return List.copyOf(findings);
    }

    private static List<String> postgres(List<String> lines) {
        Set<String> findings = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher scan = POSTGRES_SCAN.matcher(lines.get(i));
            if (!scan.find()) {
                continue;
            }
            String next = i + 1 < lines.size() ? lines.get(i + 1).strip() : "";
            String filter = next.startsWith("Filter:") ? " (" + next + ")" : "";
            findings.add("Full table scan on " + table(scan.group(1)) + filter + ADVICE);
        }
        return List.copyOf(findings);
    }

    private static List<String> mysql(List<String> columns, List<List<String>> rows) {
        int type = index(columns, "type");
        int table = index(columns, "table");
        int extra = index(columns, "Extra");
        Set<String> findings = new LinkedHashSet<>();
        for (List<String> row : rows) {
            String name = table < 0 || row.get(table) == null ? "a table" : row.get(table);
            if (type >= 0 && "ALL".equalsIgnoreCase(row.get(type))) {
                findings.add("Full table scan on " + name + ADVICE);
            }
            String notes = extra < 0 || row.get(extra) == null ? "" : row.get(extra);
            if (notes.contains("Using filesort")) {
                findings.add("Sorting " + name + " without an index (filesort): an index on the ORDER BY columns avoids the sort");
            }
            if (notes.contains("Using temporary")) {
                findings.add("A temporary table is built for " + name + ", usually for GROUP BY or DISTINCT on columns without an index");
            }
        }
        return List.copyOf(findings);
    }

    private static int index(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code PUBLIC."ORDERS"} becomes {@code ORDERS}. */
    private static String table(String qualified) {
        String name = qualified.replace("\"", "");
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
