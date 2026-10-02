// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.spring.autoconfigure.CauselineProperties.SqlCapture;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;

class SpanMapperTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT = "00f067aa0ba902b7";

    private final SpanMapper mapper = new SpanMapper("checkout-demo");

    @Test
    void serverSpanBecomesRequestNamedByRouteTemplate() {
        Span span = mapper.map(data(io.opentelemetry.api.trace.SpanKind.SERVER, "http post /api/orders",
                attrs("method", "POST", "uri", "/api/orders", "status", "201", "outcome", "SUCCESS",
                        "http.url", "/api/orders?coupon=SECRET")));

        assertThat(span.kind()).isEqualTo(SpanKind.REQUEST);
        assertThat(span.name()).isEqualTo("POST /api/orders");
        assertThat(span.source()).isEqualTo("checkout-demo");
        assertThat(span.parentSpanId()).isEqualTo(PARENT);
        assertThat(span.durationNanos()).isEqualTo(5_000_000);
        assertThat(span.attributes())
                .containsEntry("http.request.method", "POST")
                .containsEntry("http.route", "/api/orders")
                .containsEntry("http.response.status_code", "201")
                .doesNotContainKey("http.url");
        assertThat(span.attributes().values()).noneMatch(v -> v.contains("SECRET"));
    }

    @Test
    void jdbcSpanKeepsOperationAndTableButNeverSql() {
        Span span = mapper.map(data(io.opentelemetry.api.trace.SpanKind.CLIENT, "query",
                attrs("jdbc.query[0]", "insert into orders (item, quantity) values ('secret-item', 1)",
                        "jdbc.datasource.name", "dataSource")));

        assertThat(span.kind()).isEqualTo(SpanKind.DATABASE);
        assertThat(span.name()).isEqualTo("INSERT orders");
        assertThat(span.attributes())
                .containsEntry("db.operation.name", "INSERT")
                .containsEntry("db.collection.name", "orders");
        assertThat(span.attributes().values()).noneMatch(v -> v.contains("secret-item"));
    }

    @Test
    void sqlStatementIsRecordedOnlyWhenEnabledAndNeverWithLiterals() {
        Attributes query = attrs("jdbc.query[0]",
                "select * from orders o where o.customer = 'alice@example.com' and o.total > 120.50 and o.id = ?");

        Span operationOnly = new SpanMapper("checkout-demo", SqlCapture.OPERATION)
                .map(data(io.opentelemetry.api.trace.SpanKind.CLIENT, "query", query));
        Span withStatement = new SpanMapper("checkout-demo", SqlCapture.STATEMENT)
                .map(data(io.opentelemetry.api.trace.SpanKind.CLIENT, "query", query));
        Span full = new SpanMapper("checkout-demo", SqlCapture.FULL)
                .map(data(io.opentelemetry.api.trace.SpanKind.CLIENT, "query", query));

        assertThat(operationOnly.attributes()).doesNotContainKey("db.query.text");
        assertThat(withStatement.attributes()).containsEntry("db.query.text",
                "select * from orders o where o.customer = ? and o.total > ? and o.id = ?");
        assertThat(full.attributes().get("db.query.text")).contains("'alice@example.com'", "120.50");
    }

    @Test
    void fullSqlShowsTheStatementWithItsBoundValues() {
        Span span = new SpanMapper("checkout-demo", SqlCapture.FULL).map(data(io.opentelemetry.api.trace.SpanKind.CLIENT, "query",
                attrs("jdbc.query[0]", "update orders set item=?,quantity=?,status=? where id=?",
                        "jdbc.params[0]", "(book,1,PAID,42)")));

        assertThat(span.attributes())
                .containsEntry("db.query.text", "update orders set item='book',quantity=1,status='PAID' where id=42")
                .containsEntry("db.query.statement", "update orders set item=?,quantity=?,status=? where id=?")
                .containsEntry("db.query.parameters", "(book,1,PAID,42)");
    }

    @Test
    void valuesAreOnlyFilledInWhenTheyMatchThePlaceholders() {
        // A value containing a comma makes the split ambiguous: keep the ? rather than guess.
        assertThat(SpanMapper.withValues("select * from t where a=? and b=?", "(x,y,z)"))
                .isEqualTo("select * from t where a=? and b=?");
        // Batches carry several value sets for one statement.
        assertThat(SpanMapper.withValues("insert into t values (?)", "(1),(2)")).isEqualTo("insert into t values (?)");
        // A ? inside a string literal is not a placeholder; quotes in values are escaped; null stays NULL.
        assertThat(SpanMapper.withValues("select '?' from t where a=? and b=?", "(O'Brien,null)"))
                .isEqualTo("select '?' from t where a='O''Brien' and b=NULL");
    }

    @Test
    void sqlSanitizerKeepsIdentifiersWithDigitsAndHandlesEscapedQuotes() {
        assertThat(SpanMapper.sanitizeSql("insert into t1 (c2) values ('it''s', 42)"))
                .isEqualTo("insert into t1 (c2) values (?, ?)");
    }

    @Test
    void optInRequestDetailsAreRenamedToOtelConventions() {
        Span span = mapper.map(data(io.opentelemetry.api.trace.SpanKind.SERVER, "http get",
                attrs("method", "GET", "uri", "/api/orders/{id}",
                        "causeline.capture.url.query", "page=2&coupon=[REDACTED]",
                        "causeline.capture.url.path", "/api/orders/42",
                        "causeline.capture.header.x-tenant-id", "acme")));

        assertThat(span.attributes())
                .containsEntry("url.query", "page=2&coupon=[REDACTED]")
                .containsEntry("url.path", "/api/orders/42")
                .containsEntry("http.request.header.x-tenant-id", "acme")
                .doesNotContainKeys("causeline.capture.url.query", "causeline.capture.header.x-tenant-id");
    }

    @Test
    void observedMethodBecomesServiceNamedByClassAndMethod() {
        Span span = mapper.map(data(io.opentelemetry.api.trace.SpanKind.INTERNAL, "order-service#create-order",
                attrs("class", "dev.causeline.examples.checkout.OrderService", "method", "createOrder")));

        assertThat(span.kind()).isEqualTo(SpanKind.SERVICE);
        assertThat(span.name()).isEqualTo("OrderService.createOrder");
    }

    @Test
    void explicitKindWinsAndErrorsAreKept() {
        Span span = mapper.map(data(io.opentelemetry.api.trace.SpanKind.INTERNAL, "OrderController.createOrder",
                attrs(SpanMapper.KIND_ATTRIBUTE, "CONTROLLER", "outcome", "SERVER_ERROR")));

        assertThat(span.kind()).isEqualTo(SpanKind.CONTROLLER);
        assertThat(span.name()).isEqualTo("OrderController.createOrder");
        assertThat(span.status()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    void notFoundFailsTheClientButNotTheServer() {
        Attributes notFound = attrs("method", "GET", "uri", "/**", "status", "404", "outcome", "CLIENT_ERROR",
                "exception", "none");

        Span server = mapper.map(data(io.opentelemetry.api.trace.SpanKind.SERVER, "http get", notFound));
        Span client = mapper.map(data(io.opentelemetry.api.trace.SpanKind.CLIENT, "http get", notFound));

        assertThat(server.status()).isEqualTo(SpanStatus.OK);
        assertThat(server.attributes()).containsEntry("http.response.status_code", "404")
                .doesNotContainKey("exception.type");
        assertThat(client.status()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    void httpClientSpanIsNamedByHostAndTemplate() {
        Span span = mapper.map(data(io.opentelemetry.api.trace.SpanKind.CLIENT, "http post",
                attrs("method", "POST", "client.name", "localhost", "uri", "/fake-payment/charge", "status", "200")));

        assertThat(span.kind()).isEqualTo(SpanKind.HTTP_CLIENT);
        assertThat(span.name()).isEqualTo("POST localhost/fake-payment/charge");
    }

    @Test
    void kafkaAndRabbitSpansBecomeMessagesNamedByDestination() {
        Span sent = mapper.map(data(io.opentelemetry.api.trace.SpanKind.PRODUCER, "orders send",
                attrs("messaging.system", "kafka", "messaging.destination.name", "orders")));
        Span received = mapper.map(data(io.opentelemetry.api.trace.SpanKind.CONSUMER, "orders receive",
                attrs("messaging.system", "kafka", "messaging.destination.name", "orders",
                        "messaging.kafka.consumer.group", "billing")));

        assertThat(sent.kind()).isEqualTo(SpanKind.MESSAGE);
        assertThat(sent.name()).isEqualTo("send orders");
        assertThat(received.name()).isEqualTo("receive orders");
        assertThat(received.attributes()).containsEntry("messaging.system", "kafka")
                .containsEntry("messaging.consumer.group.name", "billing");
    }

    private static Attributes attrs(String... keyValues) {
        AttributesBuilder builder = Attributes.builder();
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.put(keyValues[i], keyValues[i + 1]);
        }
        return builder.build();
    }

    private static SpanData data(io.opentelemetry.api.trace.SpanKind kind, String name, Attributes attributes) {
        return TestSpanData.builder()
                .setSpanContext(SpanContext.create(TRACE, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault()))
                .setParentSpanContext(SpanContext.create(TRACE, PARENT, TraceFlags.getSampled(), TraceState.getDefault()))
                .setName(name)
                .setKind(kind)
                .setStartEpochNanos(1_000_000_000L)
                .setEndEpochNanos(1_005_000_000L)
                .setAttributes(attributes)
                .setStatus(StatusData.unset())
                .setHasEnded(true)
                .setTotalRecordedEvents(0)
                .setTotalRecordedLinks(0)
                .setTotalAttributeCount(attributes.size())
                .build();
    }
}
