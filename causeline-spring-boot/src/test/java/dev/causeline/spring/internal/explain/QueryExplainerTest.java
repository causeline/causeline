// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.spring.internal.explain.QueryExplainer.ExplainException;
import dev.causeline.spring.internal.explain.QueryExplainer.Plan;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryExplainerTest {

    private static final String APP = "checkout-demo";

    private JdbcDataSource dataSource;
    private QueryExplainer explainer;

    @BeforeEach
    void createOrders() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:explain;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop all objects");
            statement.execute("create table orders (id bigint primary key, item varchar(50), quantity int)");
            statement.execute("insert into orders values (1, 'book', 1), (2, 'pen', 3)");
        }
        explainer = new QueryExplainer(() -> dataSource, APP, Duration.ofSeconds(5));
    }

    @Test
    void pointsOutAFullTableScanAndStaysQuietWhenAnIndexIsUsed() {
        Plan scan = explainer.explain(query("select o.id from orders o where o.item = ?", "(book)"));

        assertThat(scan.database()).startsWith("H2 ");
        assertThat(scan.plan()).anyMatch(line -> line.contains("tableScan"));
        assertThat(scan.findings()).singleElement().asString().startsWith("Full table scan on ORDERS: the database reads every row");

        Plan byKey = explainer.explain(query("select o.item from orders o where o.id = ?", "(1)"));
        assertThat(byKey.findings()).isEmpty();
        assertThat(byKey.plan()).anyMatch(line -> line.contains("PRIMARY_KEY"));
    }

    @Test
    void aCapturedValueIsBoundNeverSplicedIntoTheSql() throws Exception {
        // What a request could put into a search field. Spliced into the text it would end the
        // literal; bound as a parameter it is just an item nobody ordered.
        Plan plan = explainer.explain(query("select o.id from orders o where o.item = ?", "(x' or '1'='1)"));

        assertThat(plan.findings()).hasSize(1);
        assertThat(String.join("\n", plan.plan())).doesNotContain("'1'");
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void explainsWithoutRunningAndRefusesAnythingButOneSelect() throws Exception {
        assertThatThrownBy(() -> explainer.explain(query("delete from orders where id = ?", "(1)")))
                .isInstanceOf(ExplainException.class).hasMessage("Only SELECT statements are explained.");
        assertThatThrownBy(() -> explainer.explain(query("select 1; delete from orders", null)))
                .hasMessageContaining("more than one statement");
        assertThatThrownBy(() -> explainer.explain(span(SpanKind.SERVICE, APP, Map.of())))
                .hasMessage("Only SQL queries can be explained.");

        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void refusesQueriesThatDidNotRunInThisApplication() {
        assertThatThrownBy(() -> explainer.explain(span(SpanKind.DATABASE, "inventory-service",
                Map.of("db.query.statement", "select 1"))))
                .hasMessageContaining("ran in inventory-service, not in this application");
        assertThatThrownBy(() -> explainer.explain(span(SpanKind.DATABASE, APP,
                Map.of("db.query.statement", "select 1", "causeline.forwarded", "true"))))
                .hasMessageContaining("not in this application");
    }

    @Test
    void saysWhyWhenTheSqlOrItsValuesAreNotThere() {
        assertThatThrownBy(() -> explainer.explain(span(SpanKind.DATABASE, APP, Map.of())))
                .hasMessageContaining("wasn't captured");
        // causeline.capture.sql=sanitized: literals became ?, and no values were kept.
        assertThatThrownBy(() -> explainer.explain(query("select o.id from orders o where o.item = ?", null)))
                .hasMessageContaining("causeline.capture.sql=full");
        assertThatThrownBy(() -> explainer.explain(query("select o.id from orders o where o.item = ?", "(Tolkien, J.R.R.)")))
                .hasMessageContaining("can't be matched");
        assertThatThrownBy(() -> explainer.explain(query("select o.id from orders o where o.item = 'a very long…", null)))
                .hasMessageContaining("too long");
        assertThatThrownBy(() -> new QueryExplainer(() -> null, APP, Duration.ofSeconds(5)).explain(query("select 1", null)))
                .hasMessageContaining("needs a JDBC DataSource");
        assertThatThrownBy(() -> explainer.explain(query("select nope from missing_table", null)))
                .hasMessageStartingWith("The database could not explain this query:");
    }

    @Test
    void leavesTheConnectionAsItFoundIt() throws Exception {
        explainer.explain(query("select o.id from orders o where o.quantity > ?", "(1)"));

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(connection.isReadOnly()).isFalse();
        }
    }

    @Test
    void typesValuesAndIgnoresQuestionMarksInsideLiterals() {
        assertThat(QueryExplainer.placeholders("select '?' from t where a = ? and b = \"?\" and c = ?")).isEqualTo(2);
        assertThat(QueryExplainer.values("(42,3.50,true,null,book)", 5))
                .containsExactly(42L, new java.math.BigDecimal("3.50"), true, null, "book");
        assertThat(QueryExplainer.values(null, 0)).isEmpty();
    }

    private int rowCount() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from orders")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static Span query(String statement, String parameters) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("db.query.statement", statement);
        attributes.put("db.query.text", statement);
        if (parameters != null) {
            attributes.put("db.query.parameters", parameters);
        }
        return span(SpanKind.DATABASE, APP, attributes);
    }

    private static Span span(SpanKind kind, String source, Map<String, String> attributes) {
        return new Span("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", null, kind, "SELECT orders", source, 1, 1,
                SpanStatus.OK, attributes);
    }

    /** Plans as PostgreSQL and MySQL print them: these databases aren't available to the test suite. */
    @Test
    void readsPostgresAndMysqlPlans() {
        assertThat(QueryPlans.findings("PostgreSQL", List.of("QUERY PLAN"), List.of(
                List.of("Seq Scan on orders o  (cost=0.00..18.50 rows=4 width=8)"),
                List.of("  Filter: ((item)::text = 'book'::text)"))))
                .singleElement().asString()
                .startsWith("Full table scan on orders (Filter: ((item)::text = 'book'::text)): the database reads every row");
        assertThat(QueryPlans.findings("PostgreSQL", List.of("QUERY PLAN"), List.of(
                List.of("Index Scan using orders_pkey on orders o  (cost=0.15..8.17 rows=1 width=118)"),
                List.of("  Index Cond: (id = '1'::bigint)")))).isEmpty();

        List<String> columns = List.of("id", "select_type", "table", "type", "key", "rows", "Extra");
        assertThat(QueryPlans.findings("MySQL", columns, List.of(
                java.util.Arrays.asList("1", "SIMPLE", "o", "ALL", null, "1042", "Using where; Using filesort"),
                java.util.Arrays.asList("1", "SIMPLE", "c", "eq_ref", "PRIMARY", "1", null))))
                .hasSize(2)
                .anyMatch(f -> f.startsWith("Full table scan on o:"))
                .anyMatch(f -> f.startsWith("Sorting o without an index (filesort)"));
        assertThat(QueryPlans.lines(columns, List.of(java.util.Arrays.asList("1", "SIMPLE", "o", "ALL", null, "1042", null))))
                .containsExactly("id=1  select_type=SIMPLE  table=o  type=ALL  rows=1042");
    }
}
