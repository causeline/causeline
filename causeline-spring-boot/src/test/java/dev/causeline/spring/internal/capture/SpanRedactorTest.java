// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanRedactorTest {

    private final SpanRedactor redactor = new SpanRedactor(SensitiveData.forExport(List.of("coupon")));

    @Test
    void hidesCredentialsSensitiveValuesAndSqlLiteralsOnTheWayOut() {
        Span captured = span(Map.of(
                "http.request.header.authorization", "Bearer user-token",
                "http.request.header.cookie", "SESSION=abc",
                "http.request.header.x-tenant-id", "acme",
                "url.query", "page=2&access_token=abc&coupon=SAVE10",
                "http.request.body", "{\"item\":\"book\",\"password\":\"hunter2\"}",
                "db.query.text", "select * from users where email = 'alice@example.com'"));

        Map<String, String> out = redactor.redact(captured).attributes();

        assertThat(out)
                .containsEntry("http.request.header.authorization", "[REDACTED]")
                .containsEntry("http.request.header.cookie", "[REDACTED]")
                .containsEntry("http.request.header.x-tenant-id", "acme")
                .containsEntry("url.query", "page=2&access_token=[REDACTED]&coupon=[REDACTED]")
                .containsEntry("db.query.text", "select * from users where email = ?");
        assertThat(out.get("http.request.body")).contains("\"item\":\"book\"", "\"password\":\"[REDACTED]\"")
                .doesNotContain("hunter2");
    }

    @Test
    void hidesSensitiveStateValuesAndBrowserUrls() {
        Map<String, String> out = redactor.redact(span(Map.of(
                "causeline.state.key", "passwordDraft",
                "causeline.state.value", "\"hunter2\"",
                "url.full", "https://shop.example/api/login?token=abc&next=/cart"))).attributes();

        assertThat(out).containsEntry("causeline.state.value", "[REDACTED]")
                .containsEntry("url.full", "https://shop.example/api/login?token=[REDACTED]&next=/cart");
    }

    @Test
    void sqlValuesDoNotLeaveTheApplication() {
        Map<String, String> out = redactor.redact(span(Map.of(
                "db.query.text", "select * from users where email='alice@example.com' and id=42",
                "db.query.statement", "select * from users where email=? and id=?",
                "db.query.parameters", "(alice@example.com,42)"))).attributes();

        assertThat(out).containsEntry("db.query.text", "select * from users where email=? and id=?")
                .containsEntry("db.query.parameters", "[REDACTED]");
    }

    @Test
    void redactsResponseBodiesLikeRequestBodies() {
        Map<String, String> out = redactor.redact(span(Map.of(
                "http.response.body", "{\"orderId\":1,\"accessToken\":\"eyJ-secret\"}"))).attributes();

        assertThat(out.get("http.response.body")).contains("\"orderId\":1", "\"accessToken\":\"[REDACTED]\"")
                .doesNotContain("eyJ-secret");
    }

    @Test
    void redactsMethodArgumentsAndReturnValuesOnTheWayOut() {
        Map<String, String> out = redactor.redact(span(Map.of(
                "causeline.arguments", "{\"login\":{\"user\":\"ada\",\"password\":\"hunter2\"}}",
                "causeline.return", "{\"user\":\"ada\",\"token\":\"eyJ-secret\"}"))).attributes();

        assertThat(out.get("causeline.arguments")).contains("\"user\":\"ada\"").doesNotContain("hunter2");
        assertThat(out.get("causeline.return")).contains("\"user\":\"ada\"").doesNotContain("eyJ-secret");
    }

    @Test
    void withholdsBodiesItCannotParse() {
        assertThat(redactor.redact(span(Map.of("http.request.body", "raw secret text"))).attributes())
                .containsEntry("http.request.body", "[body withheld on export]");
    }

    @Test
    void noneLeavesSpansAsCaptured() {
        Span captured = span(Map.of("http.request.header.authorization", "Bearer user-token"));

        assertThat(SpanRedactor.NONE.redact(captured)).isSameAs(captured);
    }

    private static Span span(Map<String, String> attributes) {
        return new Span("0af7651916cd43dd8448eb211c80319c", "00f067aa0ba902b7", null, SpanKind.REQUEST, "POST /api/orders",
                "app", 0, 1, SpanStatus.OK, attributes);
    }
}
