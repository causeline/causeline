// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.capture.ValueRenderer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** What each layer received and returned is on its span (causeline.capture.arguments). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"demo.payment.delay-ms=30", "causeline.access-token=values-token"})
@ActiveProfiles("dev")
class MethodValuesTest {

    private static final String TRACE_ID = "7a1f92f3577b34da6a3ce929d0e0e473";

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Autowired
    private EntityManagerFactory entityManagers;

    @Test
    void controllerServiceAndRepositoryShowTheirArgumentsAndResults() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/orders"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"lamp\",\"quantity\":3}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);

        Map<String, String> controller = awaitSpan("OrderController.createOrder").attributes();
        assertThat(controller.get("causeline.arguments")).isEqualTo("{\"request\":{\"item\":\"lamp\",\"quantity\":3}}");
        assertThat(controller.get("causeline.return")).contains("\"status\":\"PAID\"");

        Map<String, String> service = awaitSpan("OrderService.createOrder").attributes();
        assertThat(service.get("causeline.arguments")).isEqualTo("{\"item\":\"lamp\",\"quantity\":3}");
        assertThat(service.get("causeline.return")).contains("\"item\":\"lamp\"", "\"status\":\"PAID\"");

        List<Span> saves = store.get(TRACE_ID).orElseThrow().stream()
                .filter(s -> s.name().equals("OrderRepository.save")).toList();
        assertThat(saves).hasSize(2);
        assertThat(saves).anySatisfy(s -> assertThat(s.attributes().get("causeline.arguments")).contains("\"status\":\"PENDING\""));

        // A void @Async method has arguments but no return value.
        Map<String, String> mail = awaitSpan("ConfirmationMailer.sendConfirmation").attributes();
        assertThat(mail.get("causeline.arguments")).startsWith("{\"orderId\":");
        assertThat(mail).doesNotContainKey("causeline.return");
    }

    @Test
    void renderingNeverLoadsLazyJpaData() {
        long id;
        try (EntityManager em = entityManagers.createEntityManager()) {
            em.getTransaction().begin();
            Order order = new Order("desk", 1);
            em.persist(order);
            em.getTransaction().commit();
            id = order.getId();
        }
        try (EntityManager em = entityManagers.createEntityManager()) {
            Order proxy = em.getReference(Order.class, id);
            assertThat(Hibernate.isInitialized(proxy)).isFalse();

            String json = new ValueRenderer(SensitiveData.userBlocked(List.of())).value(proxy);

            assertThat(json).isEqualTo("\"[not loaded]\"");
            assertThat(Hibernate.isInitialized(proxy)).as("rendering must not run a query").isFalse();
        }
    }

    private Span awaitSpan(String name) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var found = store.get(TRACE_ID).orElse(List.of()).stream().filter(s -> s.name().equals(name)).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No span " + name + " in " + store.get(TRACE_ID).orElse(List.of()).stream().map(Span::name).toList());
    }
}
