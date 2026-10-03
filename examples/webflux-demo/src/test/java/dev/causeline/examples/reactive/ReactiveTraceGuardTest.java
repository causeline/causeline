// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.causeline.test.CauselineTest;
import dev.causeline.test.RecordedTraces;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The JUnit guard ({@code causeline-test}) on the reactive checkout, as "Copy as WebTestClient
 * test" writes it. Over a real port the answer can arrive before the server's span has ended,
 * so this also covers the guard waiting for a trace to settle.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "demo.payment.delay-ms=0")
@AutoConfigureWebTestClient
@CauselineTest
class ReactiveTraceGuardTest {

    @Autowired
    WebTestClient client;

    @Test
    void aReactiveCheckoutPassesEveryCheck(RecordedTraces causeline) {
        checkout();

        // By name: the demo's fake payment provider is untraced, so its handler starts a trace of its own.
        causeline.trace("POST /api/orders")
                .hasNoFailedSpans()
                .hasQueryCountAtMost(2)
                .hasNoRepeatedQueries()
                .hasSpans("OrderController.createOrder", "OrderService.createOrder", "OrderRepository.save",
                        "PaymentClient.charge");
    }

    @Test
    void anExtraQueryFailsTheGuard(RecordedTraces causeline) {
        checkout();

        assertThatThrownBy(() -> causeline.trace("POST /api/orders").hasQueryCount(0))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INSERT orders");
    }

    private void checkout() {
        client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"item\":\"book\",\"quantity\":2}")
                .exchange()
                .expectStatus().isCreated();
    }
}
