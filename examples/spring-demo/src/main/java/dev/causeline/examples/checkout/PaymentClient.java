// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Calls the fake payment provider over real HTTP, so the call shows up as an HTTP client span.
 *
 * <p>With the demo's "Contended" switch on, only one charge goes through at a time, like a
 * client that shares one connection or holds a lock. Alone it costs nothing; under load the wait
 * shows as this span's self time, which is what a load replay points at.
 */
@Component
class PaymentClient {

    private final RestClient.Builder builder;
    private final FakePaymentServer provider;
    private final DemoSettings settings;
    private final Semaphore oneAtATime = new Semaphore(1, true);

    PaymentClient(RestClient.Builder builder, FakePaymentServer provider, DemoSettings settings) {
        this.builder = builder;
        this.provider = provider;
        this.settings = settings;
    }

    @Observed
    public void charge(long orderId, int quantity) {
        boolean contended = settings.contendedPayment();
        if (contended) {
            oneAtATime.acquireUninterruptibly();
        }
        try {
            builder.baseUrl("http://localhost:" + provider.port()).build()
                    .post()
                    .uri("/fake-payment/charge")
                    .body(Map.of("orderId", orderId, "amount", quantity * 1200))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException.GatewayTimeout e) {
            throw new PaymentTimeoutException(orderId, e);
        } finally {
            if (contended) {
                oneAtATime.release();
            }
        }
    }
}
