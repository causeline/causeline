// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Insight.Rule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SC-13: each rule fires on a seeded request, stays silent on a clean one, and never repeats a captured value. */
class SecurityChecksTest {

    private static final long MS = 1_000_000;

    // A05 Injection

    @Test
    void flagsARequestValueConcatenatedIntoSql() {
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("url.query", "name=Robert%27s&page=2", "http.route", "/api/customers")),
                database("q", "r", "SELECT customers", Map.of(
                        "db.query.text", "SELECT * FROM customers WHERE name = 'Robert''s' LIMIT 2")));

        assertThat(security(rows)).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.REQUEST_VALUE_IN_SQL);
            assertThat(i.spanId()).isEqualTo("q");
            assertThat(i.label()).contains("query name", "SELECT customers", "A05").doesNotContain("Robert");
        });
    }

    @Test
    void boundValuesAreNotFlagged() {
        // Bound: the prepared statement keeps its placeholder; the filled-in text is only for display.
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("url.query", "name=Robert", "http.route", "/api/customers")),
                database("q", "r", "SELECT customers", Map.of(
                        "db.query.statement", "SELECT * FROM customers WHERE name = ?",
                        "db.query.parameters", "Robert",
                        "db.query.text", "SELECT * FROM customers WHERE name = 'Robert'")));

        assertThat(security(rows)).isEmpty();
    }

    @Test
    void findsPathVariablesAndBodyFieldsInSql() {
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("http.route", "/api/orders/{id}", "url.path", "/api/orders/12345",
                        "http.request.body", "{\"coupon\":\"SPRING25\",\"quantity\":2}")),
                database("q", "r", "UPDATE orders", Map.of(
                        "db.query.statement", "UPDATE orders SET coupon = 'SPRING25', quantity = ? WHERE id = 12345",
                        "db.query.parameters", "2")));

        assertThat(security(rows)).singleElement()
                .extracting(Insight::label).asString()
                .contains("path variable {id}", "body field coupon").doesNotContain("quantity");
    }

    @Test
    void shortValuesAndConstantsDoNotMatchByChance() {
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("url.query", "status=PAID&size=10&cur=EU")),
                database("q", "r", "SELECT orders", Map.of(
                        "db.query.text", "SELECT * FROM orders WHERE status = ? AND currency = 'EU' LIMIT 10",
                        "db.query.statement", "SELECT * FROM orders WHERE status = ? AND currency = 'EU' LIMIT 10",
                        "db.query.parameters", "PAID")));

        assertThat(security(rows)).isEmpty();
    }

    @Test
    void sqlLiteralsAreReadWithEscapedQuotes() {
        assertThat(SecurityChecks.literals("SELECT 'a''b', x1, 42, t.col FROM t WHERE v = -3.5"))
                .containsExactly("a'b", "42", "-3.5");
    }

    // A02 Security Misconfiguration

    @Test
    void flagsMissingHeadersOncePerTrace() {
        List<TraceView.Row> rows = List.of(
                request("a", Map.of("http.response.header.content-type", "text/html;charset=UTF-8")),
                request("b", Map.of("http.response.header.content-type", "application/json")));

        assertThat(security(rows)).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.MISSING_SECURITY_HEADERS);
            assertThat(i.spanId()).isEqualTo("a");
            assertThat(i.label()).contains("nosniff", "Content-Security-Policy", "X-Frame-Options");
        });
    }

    @Test
    void protectedResponsesAndUncapturedHeadersAreNotFlagged() {
        List<TraceView.Row> rows = List.of(
                request("a", Map.of(
                        "http.response.header.content-type", "text/html",
                        "http.response.header.x-content-type-options", "nosniff",
                        "http.response.header.content-security-policy", "default-src 'self'; frame-ancestors 'none'")),
                request("b", Map.of()));

        assertThat(security(rows)).isEmpty();
    }

    @Test
    void flagsServerVersionsAndPermissiveCors() {
        List<TraceView.Row> rows = List.of(request("a", Map.of(
                "http.response.header.content-type", "application/json",
                "http.response.header.x-content-type-options", "nosniff",
                "http.response.header.server", "Apache/2.4.1",
                "http.response.header.access-control-allow-origin", "null",
                "http.response.header.access-control-allow-credentials", "true")));

        assertThat(security(rows)).extracting(Insight::rule)
                .containsExactly(Rule.SERVER_VERSION_DISCLOSED, Rule.PERMISSIVE_CORS);
    }

    @Test
    void corsWithoutCredentialsAndAPlainServerNameAreFine() {
        List<TraceView.Row> rows = List.of(request("a", Map.of(
                "http.response.header.server", "nginx",
                "http.response.header.access-control-allow-origin", "*")));

        assertThat(security(rows)).isEmpty();
    }

    // A07 Authentication Failures

    @Test
    void flagsSessionCookiesWithoutFlagsButNotTheirValues() {
        List<TraceView.Row> rows = List.of(request("a", Map.of("http.response.header.set-cookie",
                "JSESSIONID=s3cr3t-session; Path=/\ntheme=dark; Path=/\nremember_me=abc; HttpOnly; SameSite=Lax")));

        assertThat(security(rows)).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.INSECURE_SESSION_COOKIE);
            assertThat(i.label()).contains("JSESSIONID", "HttpOnly and SameSite").doesNotContain("s3cr3t", "theme");
        });
    }

    @Test
    void flagsUnsignedAndNeverExpiringJwts() {
        String unsigned = jwt("{\"alg\":\"none\"}", "{\"sub\":\"ada\",\"exp\":1900000000}", "");
        String forever = jwt("{\"alg\":\"HS256\"}", "{\"sub\":\"ada\"}", "c2ln");
        String good = jwt("{\"alg\":\"HS256\"}", "{\"sub\":\"ada\",\"exp\":1900000000}", "c2ln");

        assertThat(security(List.of(request("a", Map.of("http.request.header.authorization", "Bearer " + unsigned)))))
                .singleElement().extracting(Insight::label).asString().contains("not signed").doesNotContain(unsigned);
        assertThat(security(List.of(request("a", Map.of("http.request.header.authorization", "Bearer " + forever)))))
                .singleElement().extracting(Insight::label).asString().contains("no expiry");
        assertThat(security(List.of(request("a", Map.of("http.request.header.authorization", "Bearer " + good)))))
                .isEmpty();
    }

    // A04 Cryptographic Failures

    @Test
    void flagsSecretsInUrlsFromServerAndBrowserOnceEach() {
        List<TraceView.Row> rows = List.of(
                browserRequest("b", Map.of("url.full", "http://localhost:5173/api/x?accessToken=t0k3n&page=1")),
                request("s", Map.of("url.query", "accessToken=t0k3n&page=1&shipping=fast")));

        assertThat(security(rows)).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.SECRET_IN_URL);
            assertThat(i.spanId()).isEqualTo("b");
            assertThat(i.label()).contains("accessToken").doesNotContain("t0k3n", "shipping");
        });
    }

    @Test
    void sensitiveKeysAreWholeWords() {
        assertThat(SecurityChecks.isSensitiveKey("api_key")).isTrue();
        assertThat(SecurityChecks.isSensitiveKey("clientSecret")).isTrue();
        assertThat(SecurityChecks.isSensitiveKey("password")).isTrue();
        assertThat(SecurityChecks.isSensitiveKey("tokenizer")).isFalse();
        assertThat(SecurityChecks.isSensitiveKey("shipping")).isFalse();
    }

    // A10 Mishandling of Exceptional Conditions

    @Test
    void flagsStackTracesAndSqlErrorsInResponses() {
        String springError = "{\"status\":500,\"trace\":\"java.lang.IllegalStateException: boom\\n\\tat "
                + "com.example.OrderService.place(OrderService.java:42)\\n\"}";
        assertThat(security(List.of(request("a", Map.of("http.response.body", springError)))))
                .singleElement().extracting(Insight::label).asString().contains("a stack trace");
        assertThat(security(List.of(request("a", Map.of("http.response.body",
                "{\"message\":\"org.h2.jdbc.JdbcSQLSyntaxErrorException: Syntax error\"}")))))
                .singleElement().extracting(Insight::label).asString().contains("a database error");
        assertThat(security(List.of(request("a", Map.of("http.response.body", "{\"error\":\"Payment failed\"}")))))
                .isEmpty();
    }

    // A09 secrets in logs

    @Test
    void flagsACredentialFromTheRequestThatEndsUpInALog() {
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("http.request.header.authorization", "Bearer s3cr3t-token-value")),
                log("l", "r", "Calling payment with token s3cr3t-token-value"));

        assertThat(security(rows)).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.SECRET_IN_LOG);
            assertThat(i.spanId()).isEqualTo("l");
            assertThat(i.label()).contains("authorization", "A09").doesNotContain("s3cr3t-token-value");
        });
    }

    @Test
    void ordinaryLogLinesAreNotFlagged() {
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("http.request.header.authorization", "Bearer s3cr3t-token-value")),
                log("l", "r", "Order 1 placed for book"));

        assertThat(security(rows)).isEmpty();
    }

    // A04 secrets in the response

    @Test
    void flagsSecretFieldsReturnedInAResponse() {
        List<TraceView.Row> rows = List.of(request("r", Map.of(
                "http.response.body", "{\"id\":1,\"passwordHash\":\"$2a$deadbeef\",\"email\":\"a@b.c\",\"apiKey\":\"\"}")));

        assertThat(security(rows)).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.SENSITIVE_FIELD_IN_RESPONSE);
            // apiKey is empty, so only the field with a real value is flagged, and never its value.
            assertThat(i.label()).contains("passwordHash", "A04").doesNotContain("deadbeef", "apiKey", "email");
        });
    }

    // SSRF

    @Test
    void flagsARequestValueThatSteersAnOutboundCall() {
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("url.query", "callbackUrl=http://169.254.169.254/latest")),
                new TraceView.Row("c", "r", SpanKind.HTTP_CLIENT, "GET http://169.254.169.254/latest", "checkout",
                        SpanStatus.OK, 1, MS, MS, MS, false,
                        Map.of("url.full", "http://169.254.169.254/latest/meta-data")));

        assertThat(security(rows)).singleElement().satisfies(i -> {
            assertThat(i.rule()).isEqualTo(Rule.REQUEST_VALUE_IN_OUTBOUND_URL);
            assertThat(i.spanId()).isEqualTo("c");
            assertThat(i.label()).contains("query callbackUrl", "A10");
        });
    }

    @Test
    void anOutboundCallToAFixedUrlIsNotFlagged() {
        List<TraceView.Row> rows = List.of(
                request("r", Map.of("url.query", "item=book&quantity=1")),
                new TraceView.Row("c", "r", SpanKind.HTTP_CLIENT, "POST localhost/fake-payment/charge", "checkout",
                        SpanStatus.OK, 1, MS, MS, MS, false, Map.of("url.full", "http://localhost/fake-payment/charge")));

        assertThat(security(rows)).isEmpty();
    }

    @Test
    void aHugeStackTraceBodyIsFlaggedWithoutOverflowing() {
        // A full stack trace in a response body is thousands of characters; the scan must not recurse per char.
        StringBuilder trace = new StringBuilder("java.lang.IllegalStateException: boom");
        for (int i = 0; i < 2000; i++) {
            trace.append("\\n\\tat com.example.Service.call(Service.java:").append(i).append(')');
        }
        List<TraceView.Row> rows = List.of(request("r", Map.of(
                "http.response.body", "{\"error\":\"Internal error\",\"trace\":\"" + trace + "\"}")));

        assertThat(security(rows)).singleElement().extracting(Insight::rule).isEqualTo(Rule.ERROR_DETAILS_EXPOSED);
    }

    @Test
    void theSecurityRulesAreMarkedAsSuchOnTheEnum() {
        assertThat(Rule.SECRET_IN_LOG.isSecurity()).isTrue();
        assertThat(Rule.REQUEST_VALUE_IN_SQL.isSecurity()).isTrue();
        assertThat(Rule.REQUEST_VALUE_IN_OUTBOUND_URL.isSecurity()).isTrue();
        assertThat(Rule.PRIMARY_BOTTLENECK.isSecurity()).isFalse();
        assertThat(Rule.HANDLED_EXCEPTION.isSecurity()).isFalse();
    }

    // Helpers

    private static TraceView.Row log(String id, String parent, String message) {
        return new TraceView.Row(id, parent, SpanKind.LOG, "INFO OrderService: " + message, "checkout", SpanStatus.OK, 1,
                MS, 0, 0, false, Map.of("log.message", message, "log.level", "INFO",
                        "log.logger", "dev.causeline.examples.OrderService"));
    }

    private static List<Insight> security(List<TraceView.Row> rows) {
        List<Insight> insights = new ArrayList<>();
        SecurityChecks.analyze(rows, insights);
        // Insights.analyze runs the same rules alongside the others.
        assertThat(Insights.analyze(rows, 10 * MS)).containsAll(insights);
        return insights;
    }

    private static TraceView.Row request(String id, Map<String, String> attributes) {
        return new TraceView.Row(id, null, SpanKind.REQUEST, "GET /api/" + id, "checkout", SpanStatus.OK, 0, 0, 10 * MS,
                10 * MS, false, new LinkedHashMap<>(attributes));
    }

    private static TraceView.Row browserRequest(String id, Map<String, String> attributes) {
        return new TraceView.Row(id, null, SpanKind.REQUEST, "GET /api/x", "browser", SpanStatus.OK, 0, 0, 10 * MS,
                10 * MS, false, attributes);
    }

    private static TraceView.Row database(String id, String parent, String name, Map<String, String> attributes) {
        return new TraceView.Row(id, parent, SpanKind.DATABASE, name, "checkout", SpanStatus.OK, 1, MS, MS, MS, false,
                attributes);
    }

    private static String jwt(String header, String payload, String signature) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;
    }
}
