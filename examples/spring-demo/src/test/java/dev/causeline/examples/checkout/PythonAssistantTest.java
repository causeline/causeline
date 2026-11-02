// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SC-22, Spring's half: the call to the Python service carries the trace on. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "demo.model.port=18093")
@ActiveProfiles("dev")
class PythonAssistantTest {

    private static HttpServer python;
    private static final AtomicReference<String> traceparent = new AtomicReference<>();
    private static final AtomicReference<String> body = new AtomicReference<>();

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @BeforeAll
    static void startPython() throws IOException {
        python = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        python.createContext("/ask", exchange -> {
            try (exchange) {
                traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] reply = "{\"answer\":\"from python\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
            }
        });
        python.start();
    }

    @AfterAll
    static void stopPython() {
        python.stop(0);
    }

    @DynamicPropertySource
    static void pythonUrl(DynamicPropertyRegistry registry) {
        registry.add("demo.python.url", () -> "http://localhost:" + python.getAddress().getPort());
    }

    @Test
    void theCallToPythonContinuesTheTrace() throws Exception {
        String traceId = "a4f92f3577b34da6a3ce929d0e0e4736";

        HttpResponse<String> response = ask(traceId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("from python");
        assertThat(body.get()).contains("When are refunds issued?").contains("reranked");

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Span> clients = List.of();
        while (System.nanoTime() < deadline && clients.isEmpty()) {
            clients = store.get(traceId).orElse(List.of()).stream().filter(s -> s.kind() == SpanKind.HTTP_CLIENT).toList();
            Thread.sleep(50);
        }
        assertThat(clients).hasSize(1);
        assertThat(traceparent.get()).isEqualTo("00-" + traceId + "-" + clients.getFirst().spanId() + "-01");
    }

    private HttpResponse<String> ask(String traceId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + environment.getProperty("local.server.port") + "/api/ask/python"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"When are refunds issued?\",\"mode\":\"reranked\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
