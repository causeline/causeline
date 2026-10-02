// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Stands in for an external payment provider: slow by default, and it times out for the item
 * "declined" so a failing checkout is one request away.
 */
@RestController
class FakePaymentController {

    private final Duration delay;

    FakePaymentController(@Value("${demo.payment.delay-ms:600}") long delayMillis) {
        this.delay = Duration.ofMillis(delayMillis);
    }

    @PostMapping("/fake-payment/charge")
    Mono<ResponseEntity<Map<String, String>>> charge(@RequestBody Map<String, Object> payment) {
        boolean declined = ((Number) payment.getOrDefault("amount", 0)).intValue() > 100_000;
        return Mono.delay(delay).map(tick -> declined
                ? ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("status", "TIMEOUT"))
                : ResponseEntity.ok(Map.of("status", "APPROVED")));
    }
}
