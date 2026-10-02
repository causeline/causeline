// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Calls the fake payment service over real HTTP, so the call shows up as an HTTP client span.
 * The payment service runs inside this demo app to keep the example to one process.
 */
@Component
class PaymentClient {

    private final RestClient.Builder builder;
    private final Environment environment;

    PaymentClient(RestClient.Builder builder, Environment environment) {
        this.builder = builder;
        this.environment = environment;
    }

    @Observed
    public void charge(long orderId, int quantity) {
        // The port is only known once the server has started (tests use a random port).
        String baseUrl = "http://localhost:" + environment.getProperty("local.server.port", "8080");
        try {
            builder.baseUrl(baseUrl).build()
                    .post()
                    .uri("/fake-payment/charge")
                    .body(Map.of("orderId", orderId, "amount", quantity * 1200))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException.GatewayTimeout e) {
            throw new PaymentTimeoutException(orderId, e);
        }
    }
}
