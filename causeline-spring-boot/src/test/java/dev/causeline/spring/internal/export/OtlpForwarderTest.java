// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.spring.autoconfigure.TestProperties;
import dev.causeline.spring.internal.tracing.CauselineStats;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OtlpForwarderTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer collector;

    @AfterEach
    void stop() {
        if (collector != null) {
            collector.stop(0);
        }
    }

    @Test
    void sendsSpansAsOtlpJsonGroupedByService() throws Exception {
        CauselineStats stats = new CauselineStats();
        OtlpForwarder forwarder = forwarder(stats);

        forwarder.forward(List.of(
                span("a000000000000001", null, SpanKind.UI_ACTION, "Checkout", "browser", SpanStatus.OK, Map.of()),
                span("b000000000000001", "a000000000000001", SpanKind.REQUEST, "POST /api/orders", "checkout-demo",
                        SpanStatus.ERROR, Map.of("http.response.status_code", "500", "note", "say \"hi\"\n"))));
        forwarder.flush();

        JsonNode resources = JsonMapper.builder().build().readTree(body.get()).get("resourceSpans");
        assertThat(resources).hasSize(2);
        assertThat(resources.get(0).toString()).contains("\"checkout-demo-browser\"");
        JsonNode server = resources.get(1);
        assertThat(server.toString()).contains("\"service.name\"", "\"checkout-demo\"");
        JsonNode request = server.get("scopeSpans").get(0).get("spans").get(0);
        assertThat(request.get("traceId").asString()).isEqualTo(TRACE);
        assertThat(request.get("parentSpanId").asString()).isEqualTo("a000000000000001");
        assertThat(request.get("kind").asInt()).isEqualTo(2);
        assertThat(request.get("status").get("code").asInt()).isEqualTo(2);
        assertThat(request.get("startTimeUnixNano").asString()).isEqualTo("1000");
        assertThat(request.get("endTimeUnixNano").asString()).isEqualTo("1500");
        assertThat(request.toString()).contains("say \\\"hi\\\"\\n");
        assertThat(apiKey.get()).isEqualTo("dd-key");
        assertThat(stats.otlpExported.get()).isEqualTo(2);
    }

    @Test
    void countsFailuresAndDropsInsteadOfRetrying() throws Exception {
        CauselineStats stats = new CauselineStats();
        status.set(503);
        OtlpForwarder forwarder = forwarder(stats);

        forwarder.forward(List.of(span("b000000000000001", null, SpanKind.REQUEST, "GET /", "app", SpanStatus.OK, Map.of())));
        forwarder.flush();

        assertThat(stats.otlpFailedRequests.get()).isEqualTo(1);
        assertThat(stats.otlpDropped.get()).isEqualTo(1);
        assertThat(stats.otlpExported.get()).isZero();
    }

    private OtlpForwarder forwarder(CauselineStats stats) throws Exception {
        collector = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("DD-API-KEY"));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        collector.start();
        var properties = TestProperties.bind(Map.of(
                "export.otlp.endpoint", "http://localhost:" + collector.getAddress().getPort() + "/v1/traces",
                "export.otlp.headers.DD-API-KEY", "dd-key"));
        return new OtlpForwarder(properties.export().otlp(), "checkout-demo", stats, Duration.ofHours(1));
    }

    private static Span span(String id, String parent, SpanKind kind, String name, String source, SpanStatus status,
            Map<String, String> attributes) {
        return new Span(TRACE, id, parent, kind, name, source, 1000, 500, status, attributes);
    }
}
