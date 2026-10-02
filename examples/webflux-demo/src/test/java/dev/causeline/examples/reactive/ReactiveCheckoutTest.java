// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** Causeline in a WebFlux application: the same linked trace as the servlet demo, with reactive timing. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"demo.payment.delay-ms=" + ReactiveCheckoutTest.PAYMENT_DELAY_MS,
                "causeline.access-token=" + ReactiveCheckoutTest.TOKEN,
                "causeline.capture.redact-keys=pin", "causeline.capture.headers.block=X-Internal"})
@ActiveProfiles("dev")
class ReactiveCheckoutTest {

    static final String TOKEN = "test-token";
    static final long PAYMENT_DELAY_MS = 200;
    private static final String BROWSER_SPAN_ID = "00f067aa0ba902b7";

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Test
    void reactiveCheckoutProducesAFullyLinkedTrace() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        HttpResponse<String> response = post("/api/orders", "{\"item\":\"book\",\"quantity\":2}", traceparent(traceId));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"status\":\"PAID\"");

        List<Span> spans = await(traceId, s -> s.stream().anyMatch(span -> span.name().equals("UPDATE orders"))
                && s.stream().anyMatch(span -> span.name().equals("OrderService.createOrder")));
        Map<String, Span> byId = spans.stream().collect(Collectors.toMap(Span::spanId, s -> s));

        Span server = find(spans, s -> s.kind() == SpanKind.REQUEST && s.name().equals("POST /api/orders"));
        assertThat(server.parentSpanId()).isEqualTo(BROWSER_SPAN_ID);
        assertThat(server.source()).isEqualTo("reactive-checkout");

        Span controller = find(spans, s -> s.name().equals("OrderController.createOrder"));
        Span service = find(spans, s -> s.name().equals("OrderService.createOrder"));
        Span payment = find(spans, s -> s.name().equals("PaymentClient.charge"));
        Span call = find(spans, s -> s.kind() == SpanKind.HTTP_CLIENT && s.name().contains("/fake-payment/charge"));
        Span insert = find(spans, s -> s.kind() == SpanKind.DATABASE && s.name().equals("INSERT orders"));
        Span save = find(spans, s -> s.kind() == SpanKind.REPOSITORY && s.name().equals("OrderRepository.save"));

        // The tree: request > controller > service > (repository > query, payment > HTTP call).
        assertThat(controller.parentSpanId()).isEqualTo(server.spanId());
        assertThat(service.parentSpanId()).isEqualTo(controller.spanId());
        assertThat(payment.parentSpanId()).isEqualTo(service.spanId());
        assertThat(call.parentSpanId()).isEqualTo(payment.spanId());
        assertThat(ancestors(insert, byId)).contains(save.spanId(), service.spanId());

        // Reactive spans last until the Mono finishes, not until the method returns it.
        long delay = Duration.ofMillis(PAYMENT_DELAY_MS).toNanos();
        assertThat(service.durationNanos()).isGreaterThanOrEqualTo(delay);
        assertThat(controller.durationNanos()).isGreaterThanOrEqualTo(delay);
        assertThat(payment.durationNanos()).isGreaterThanOrEqualTo(delay);

        // What each layer received and produced, and the query as it ran.
        assertThat(service.attributes().get("causeline.arguments")).isEqualTo("{\"item\":\"book\",\"quantity\":2}");
        assertThat(service.attributes().get("causeline.return")).contains("\"status\":\"PAID\"");
        assertThat(payment.attributes().get("causeline.return")).isEqualTo("\"APPROVED\"");
        assertThat(insert.attributes().get("db.query.text")).contains("'book'").contains("2");

        // Request details and bodies on the server span.
        assertThat(server.attributes()).containsEntry("http.request.header.x-demo", "reactive");
        assertThat(server.attributes().get("http.request.body")).isEqualTo("{\"item\":\"book\",\"quantity\":2}");
        assertThat(server.attributes().get("http.response.body")).contains("\"status\":\"PAID\"");
    }

    @Test
    void secretsAreShownLocallyButRedactedInExports() throws Exception {
        String traceId = "abf92f3577b34da6a3ce929d0e0e4736";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/orders?coupon=SECRET-QUERY&token=SECRET-TOKEN"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer SECRET-AUTH")
                .header("X-Internal", "SECRET-BLOCKED-HEADER")
                .header("traceparent", traceparent(traceId))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"item\":\"SECRET-ITEM\",\"quantity\":1,\"password\":\"SECRET-PASSWORD\",\"pin\":\"SECRET-PIN\"}"))
                .build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(201);

        List<Span> spans = await(traceId, s -> s.stream().anyMatch(span -> span.name().equals("UPDATE orders")));
        Span server = find(spans, s -> s.kind() == SpanKind.REQUEST && s.name().equals("POST /api/orders"));
        // Shown locally by default...
        assertThat(server.attributes())
                .containsEntry("http.request.header.authorization", "Bearer SECRET-AUTH")
                .containsEntry("url.query", "coupon=SECRET-QUERY&token=SECRET-TOKEN");
        assertThat(server.attributes().get("http.request.body")).contains("SECRET-PASSWORD");
        // ...except what the user blocked, which is hidden even locally.
        assertThat(spans.toString()).doesNotContain("SECRET-PIN", "SECRET-BLOCKED-HEADER");

        String exported = get("/causeline/api/traces/" + traceId + "/export", null, TOKEN).body();
        assertThat(exported).doesNotContain("SECRET-AUTH", "SECRET-TOKEN", "SECRET-PASSWORD", "SECRET-PIN");
        assertThat(exported).contains("SECRET-ITEM");
    }

    @Test
    void fluxResultsAreShownAsTheItemsTheyProduced() throws Exception {
        assertThat(post("/api/orders", "{\"item\":\"lamp\",\"quantity\":1}", null).statusCode()).isEqualTo(201);
        String traceId = "7bf92f3577b34da6a3ce929d0e0e4736";
        HttpResponse<String> response = get("/api/orders?item=lamp", traceparent(traceId), null);
        assertThat(response.statusCode()).isEqualTo(200);

        List<Span> spans = await(traceId, s -> s.stream().anyMatch(span -> span.name().equals("OrderService.ordersFor")));
        Span service = find(spans, s -> s.name().equals("OrderService.ordersFor"));
        assertThat(service.attributes().get("causeline.return")).startsWith("[").contains("\"item\":\"lamp\"");
        assertThat(spans).anyMatch(s -> s.kind() == SpanKind.DATABASE && s.name().equals("SELECT orders"));
    }

    @Test
    void failedPaymentFailsTheTrace() throws Exception {
        String traceId = "8bf92f3577b34da6a3ce929d0e0e4736";
        HttpResponse<String> response = post("/api/orders", "{\"item\":\"piano\",\"quantity\":100}", traceparent(traceId));
        assertThat(response.statusCode()).isEqualTo(500);

        List<Span> spans = await(traceId, s -> s.stream().anyMatch(span -> span.name().equals("POST /api/orders")));
        assertThat(find(spans, s -> s.name().equals("POST /api/orders")).status()).isEqualTo(SpanStatus.ERROR);
        assertThat(find(spans, s -> s.name().equals("OrderService.createOrder")).status()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    void uiAndApiAreServedAndGuarded() throws Exception {
        HttpResponse<String> ui = get("/causeline/", null, null);
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("<div id=\"root\">");

        assertThat(get("/causeline/api/traces", null, null).statusCode()).isEqualTo(401);

        String traceId = "5bf92f3577b34da6a3ce929d0e0e4736";
        assertThat(get("/causeline/api/traces", traceparent(traceId), TOKEN).statusCode()).isEqualTo(200);
        Thread.sleep(500);
        assertThat(store.get(traceId)).as("Causeline's own requests are not traced").isEmpty();
    }

    @Test
    void capturedRequestsCanBeReplayed() throws Exception {
        String traceId = "9bf92f3577b34da6a3ce929d0e0e4736";
        assertThat(post("/api/orders", "{\"item\":\"pen\",\"quantity\":1}", traceparent(traceId)).statusCode())
                .isEqualTo(201);
        List<Span> spans = await(traceId, s -> s.stream().anyMatch(span -> span.name().equals("POST /api/orders")));
        String spanId = find(spans, s -> s.name().equals("POST /api/orders")).spanId();

        HttpResponse<String> replayable = get("/causeline/api/traces/" + traceId + "/replayable", null, TOKEN);
        assertThat(replayable.body()).contains(spanId).contains("\"hasBody\":true");

        HttpResponse<String> replay = post("/causeline/api/replays", """
                {"traceId":"%s","spanId":"%s","target":"local","confirm":true}""".formatted(traceId, spanId), null, TOKEN);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("\"httpStatus\":201");
    }

    private List<Span> await(String traceId, Predicate<List<Span>> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Span> spans = List.of();
        while (System.nanoTime() < deadline) {
            spans = store.get(traceId).orElse(List.of());
            if (done.test(spans)) {
                return spans;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Trace incomplete: " + spans.stream().map(s -> s.kind() + " " + s.name()).toList());
    }

    private static Span find(List<Span> spans, Predicate<Span> match) {
        return spans.stream().filter(match).findFirst().orElseThrow(() -> new AssertionError(
                "No such span in " + spans.stream().map(s -> s.kind() + " " + s.name()).toList()));
    }

    private static List<String> ancestors(Span span, Map<String, Span> byId) {
        List<String> ids = new java.util.ArrayList<>();
        for (Span parent = byId.get(span.parentSpanId()); parent != null; parent = byId.get(parent.parentSpanId())) {
            ids.add(parent.spanId());
        }
        return ids;
    }

    private static String traceparent(String traceId) {
        return "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01";
    }

    private HttpResponse<String> post(String path, String json, String traceparent) throws Exception {
        return post(path, json, traceparent, null);
    }

    private HttpResponse<String> post(String path, String json, String traceparent, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Demo", "reactive")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        if (token != null) {
            request.header("X-Causeline-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String traceparent, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        if (token != null) {
            request.header("X-Causeline-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
