// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Calls the fake payment provider over real HTTP, so the call shows up as an HTTP client span. */
@Component
class PaymentClient {

    private final WebClient.Builder builder;
    private final Environment environment;

    PaymentClient(WebClient.Builder builder, Environment environment) {
        this.builder = builder;
        this.environment = environment;
    }

    @Observed
    public Mono<String> charge(long orderId, int quantity) {
        // The port is only known once the server has started (tests use a random port).
        String baseUrl = "http://localhost:" + environment.getProperty("local.server.port", "8080");
        return builder.baseUrl(baseUrl).build()
                .post()
                .uri("/fake-payment/charge")
                .bodyValue(Map.of("orderId", orderId, "amount", quantity * 1200))
                .retrieve()
                .bodyToMono(PaymentResult.class)
                .map(PaymentResult::status);
    }

    record PaymentResult(String status) {
    }
}
