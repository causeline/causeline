// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** End-to-end check of the Spring side of milestone 0.1 (SC-03, SC-04). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"demo.payment.delay-ms=150", "causeline.access-token=" + CheckoutApplicationTest.TOKEN})
@ActiveProfiles("dev")
class CheckoutApplicationTest {

    static final String TOKEN = "test-token";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String BROWSER_SPAN_ID = "00f067aa0ba902b7";

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Test
    void checkoutRequestProducesAFullyLinkedBackendTrace() throws Exception {
        HttpResponse<String> response = post("/api/orders", "{\"item\":\"book\",\"quantity\":1}",
                "00-" + TRACE_ID + "-" + BROWSER_SPAN_ID + "-01");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"status\":\"PAID\"");

        List<Span> spans = awaitSpans(TRACE_ID, List.of(SpanKind.REQUEST, SpanKind.CONTROLLER, SpanKind.SERVICE,
                SpanKind.REPOSITORY, SpanKind.DATABASE, SpanKind.HTTP_CLIENT));

        // SC-04: the server span continues the browser's trace and hangs under the browser span.
        Span server = spans.stream()
                .filter(s -> s.kind() == SpanKind.REQUEST && s.name().equals("POST /api/orders"))
                .findFirst().orElseThrow();
        assertThat(server.parentSpanId()).isEqualTo(BROWSER_SPAN_ID);
        assertThat(server.source()).isEqualTo("checkout-demo");

        assertThat(spans).extracting(Span::name).contains(
                "OrderController.createOrder",
                "OrderService.createOrder",
                "OrderRepository.save",
                "INSERT orders");
        assertThat(spans).anyMatch(s -> s.kind() == SpanKind.HTTP_CLIENT && s.name().contains("/fake-payment/charge"));
    }

    @Test
    void causelineOwnRequestsAreNotTraced() throws Exception {
        String traceId = "5bf92f3577b34da6a3ce929d0e0e4736";
        HttpResponse<String> response = get("/causeline/api/traces", "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01");

        assertThat(response.statusCode()).isEqualTo(200);
        Thread.sleep(500);
        assertThat(store.get(traceId)).isEmpty();
    }

    @Test
    void acceptsBrowserSpansAndServesTheAssembledTrace() throws Exception {
        String traceId = "6bf92f3577b34da6a3ce929d0e0e4736";
        String body = """
                [{"traceId":"%s","spanId":"a000000000000001","parentSpanId":null,"kind":"UI_ACTION",
                  "name":"Checkout","startTimeUnixNano":"1790612345123456000","durationNanos":1000000,
                  "status":"OK","attributes":{}},
                 {"traceId":"%s","spanId":"a000000000000002","parentSpanId":null,"kind":"DATABASE",
                  "name":"forged","startTimeUnixNano":"1","durationNanos":1,"status":"OK","attributes":{}}]
                """.formatted(traceId, traceId);

        HttpResponse<String> ingest = post("/causeline/api/spans", body, null);
        assertThat(ingest.statusCode()).isEqualTo(202);
        assertThat(ingest.body()).contains("\"accepted\":1").contains("\"rejected\":1");

        HttpResponse<String> trace = get("/causeline/api/traces/" + traceId, null);
        assertThat(trace.statusCode()).isEqualTo(200);
        assertThat(trace.body()).contains("\"name\":\"Checkout\"").doesNotContain("forged");
    }

    private List<Span> awaitSpans(String traceId, List<SpanKind> expectedKinds) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Span> spans = List.of();
        while (System.nanoTime() < deadline) {
            Optional<List<Span>> found = store.get(traceId);
            spans = found.orElse(List.of());
            if (spans.stream().map(Span::kind).toList().containsAll(expectedKinds)) {
                return spans;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Expected span kinds " + expectedKinds + " but trace had: "
                + spans.stream().map(s -> s.kind() + " " + s.name()).toList());
    }

    private HttpResponse<String> post(String path, String json, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Autowired
    private DemoSettings settings;

    @Test
    void failedPaymentShowsTheExceptionAndWhereItWasThrown() throws Exception {
        String traceId = "7bf92f3577b34da6a3ce929d0e0e4736";
        settings.update(new DemoSettings.Snapshot(true, true, false));
        try {
            HttpResponse<String> response = post("/api/orders", "{\"item\":\"book\",\"quantity\":1}",
                    "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01");
            assertThat(response.statusCode()).isEqualTo(500);
        } finally {
            settings.update(new DemoSettings.Snapshot(true, false, false));
        }

        // SC-06: the application's exception, located in application code, and the trace marked as failed.
        // The 504 that caused it is recorded separately on the HTTP client span, so the cause is visible too.
        List<Span> spans = awaitSpans(traceId, List.of(SpanKind.EXCEPTION, SpanKind.REQUEST));
        assertThat(spans).filteredOn(s -> s.kind() == SpanKind.EXCEPTION)
                .extracting(Span::name)
                .containsExactlyInAnyOrder("PaymentTimeoutException", "GatewayTimeout");
        assertThat(spans).filteredOn(s -> s.name().equals("PaymentTimeoutException"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.name()).isEqualTo("PaymentTimeoutException");
                    assertThat(e.attributes().get("code.location")).startsWith("PaymentClient.java:");
                    assertThat(e.attributes()).containsEntry("code.function", "PaymentClient.charge");
                });
        assertThat(get("/causeline/api/traces/" + traceId, null).body()).contains("\"status\":\"ERROR\"");
    }

    @Test
    void caughtAndLoggedExceptionIsShownWithoutFailingTheTrace() throws Exception {
        String traceId = "abf92f3577b34da6a3ce929d0e0e4736";
        settings.update(new DemoSettings.Snapshot(true, false, true));
        try {
            HttpResponse<String> response = post("/api/orders", "{\"item\":\"book\",\"quantity\":1}",
                    "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01");
            assertThat(response.statusCode()).isEqualTo(201);
        } finally {
            settings.update(new DemoSettings.Snapshot(true, false, false));
        }

        List<Span> spans = awaitSpans(traceId, List.of(SpanKind.EXCEPTION, SpanKind.HTTP_CLIENT));
        Span stockCheck = spans.stream().filter(s -> s.name().equals("StockService.isInStock")).findFirst().orElseThrow();
        assertThat(spans).filteredOn(s -> s.kind() == SpanKind.EXCEPTION)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.name()).isEqualTo("InventoryUnavailableException");
                    assertThat(e.parentSpanId()).isEqualTo(stockCheck.spanId());
                    assertThat(e.attributes())
                            .containsEntry("causeline.exception.handled", "true")
                            .containsEntry("log.level", "WARN")
                            .containsEntry("code.function", "StockService.askInventory");
                    assertThat(e.attributes().get("code.location")).startsWith("StockService.java:");
                });
        assertThat(get("/causeline/api/traces/" + traceId, null).body()).contains("\"status\":\"OK\"");
    }

    @Test
    void slowPaymentIsFlaggedAsThePrimaryBottleneck() throws Exception {
        post("/api/orders", "{\"item\":\"warm-up\",\"quantity\":1}", null);
        String traceId = "8bf92f3577b34da6a3ce929d0e0e4736";
        post("/api/orders", "{\"item\":\"book\",\"quantity\":1}", "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01");
        awaitSpans(traceId, List.of(SpanKind.HTTP_CLIENT, SpanKind.REQUEST));

        // SC-07
        String view = get("/causeline/api/traces/" + traceId, null).body();
        assertThat(view).contains("\"rule\":\"PRIMARY_BOTTLENECK\"")
                .contains("Potential bottleneck: POST localhost/fake-payment/charge");
    }

    @Test
    void secretsAreShownLocallyButRedactedInExports() throws Exception {
        String traceId = "9bf92f3577b34da6a3ce929d0e0e4736";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/orders?coupon=SECRET-QUERY&token=SECRET-TOKEN"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer SECRET-AUTH")
                .header("Cookie", "SESSION=SECRET-COOKIE")
                .header("X-API-Key", "SECRET-KEY")
                .header("traceparent", "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"item\":\"SECRET-ITEM\",\"quantity\":1,\"password\":\"SECRET-PASSWORD\"}"))
                .build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(201);

        // The local UI shows everything that was sent, credentials included (the default).
        List<Span> spans = awaitSpans(traceId, List.of(SpanKind.DATABASE, SpanKind.HTTP_CLIENT));
        Span server = spans.stream().filter(s -> s.kind() == SpanKind.REQUEST && s.name().equals("POST /api/orders"))
                .findFirst().orElseThrow();
        assertThat(server.attributes())
                .containsEntry("http.request.header.authorization", "Bearer SECRET-AUTH")
                .containsEntry("http.request.header.cookie", "SESSION=SECRET-COOKIE")
                .containsEntry("url.query", "coupon=SECRET-QUERY&token=SECRET-TOKEN");
        assertThat(server.attributes().get("http.request.body")).contains("SECRET-PASSWORD");
        // The response body is recorded too, and the client still got it in full.
        assertThat(server.attributes().get("http.response.body")).contains("\"status\":\"PAID\"", "\"orderId\"");

        // SC-11 (revised): anything leaving the application has credentials redacted.
        String exported = get("/causeline/api/traces/" + traceId + "/export", null).body();
        assertThat(exported).doesNotContain("SECRET-AUTH", "SECRET-COOKIE", "SECRET-KEY", "SECRET-TOKEN", "SECRET-PASSWORD");
        assertThat(exported).contains("SECRET-ITEM"); // ordinary data stays useful
    }

    @Test
    void exportedTraceCanBeImportedElsewhere() throws Exception {
        String traceId = "ccf92f3577b34da6a3ce929d0e0e4736";
        post("/api/orders", "{\"item\":\"book\",\"quantity\":1}", "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01");
        awaitSpans(traceId, List.of(SpanKind.REQUEST, SpanKind.HTTP_CLIENT));

        HttpResponse<String> exported = get("/causeline/api/traces/" + traceId + "/export", null);
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(exported.headers().firstValue("Content-Disposition")).get().asString()
                .contains("causeline-trace-ccf92f35.json");
        assertThat(exported.body()).contains("\"format\":\"causeline-trace\"", "\"exportedFrom\":\"checkout-demo\"");

        // Importing into the app that already has it is refused...
        HttpRequest again = withToken("/causeline/api/traces/import").POST(HttpRequest.BodyPublishers.ofString(exported.body())).build();
        assertThat(HttpClient.newHttpClient().send(again, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);

        // ...but the same file as another developer's trace is loaded, pinned and marked as imported.
        String otherTraceId = "ddf92f3577b34da6a3ce929d0e0e4736";
        HttpRequest imported = withToken("/causeline/api/traces/import")
                .POST(HttpRequest.BodyPublishers.ofString(exported.body().replace(traceId, otherTraceId))).build();
        HttpResponse<String> result = HttpClient.newHttpClient().send(imported, HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(store.isPinned(otherTraceId)).isTrue();
        assertThat(get("/causeline/api/traces", null).body())
                .contains("\"traceId\":\"" + otherTraceId + "\"")
                .containsPattern("\"traceId\":\"" + otherTraceId + "\"[^}]*\"imported\":true");

        HttpRequest garbage = withToken("/causeline/api/traces/import")
                .POST(HttpRequest.BodyPublishers.ofString("{\"format\":\"something-else\",\"schemaVersion\":1,\"spans\":[]}")).build();
        assertThat(HttpClient.newHttpClient().send(garbage, HttpResponse.BodyHandlers.ofString()).body())
                .contains("Not a Causeline trace file");
    }

    @Test
    void statusReportsWhereDataWasLost() throws Exception {
        String traceId = "eef92f3577b34da6a3ce929d0e0e4736";
        HttpRequest upload = HttpRequest.newBuilder(uri("/causeline/api/spans"))
                .header("Content-Type", "application/json")
                .header("X-Causeline-Dropped", "7")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        [{"traceId":"%s","spanId":"a000000000000001","parentSpanId":null,"kind":"UI_ACTION",
                          "name":"Checkout","startTimeUnixNano":"1790612345123456000","durationNanos":1,
                          "status":"OK","attributes":{}}]""".formatted(traceId)))
                .build();
        assertThat(HttpClient.newHttpClient().send(upload, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);

        String status = get("/causeline/api/status", null).body();
        assertThat(status).contains("\"serverSpansDropped\":0", "\"maxBytes\":67108864", "\"otlp\":{\"enabled\":false");
        assertThat(status).containsPattern("\"browserSpansDropped\":([7-9]|\\d{2,})");
    }

    private HttpRequest.Builder withToken(String path) {
        return HttpRequest.newBuilder(uri(path)).header("X-Causeline-Token", TOKEN).header("Content-Type", "application/json");
    }

    @Test
    void causelineApiRejectsRequestsWithoutTheToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/causeline/api/traces")).GET().build();

        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String traceparent) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET().header("X-Causeline-Token", TOKEN);
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
