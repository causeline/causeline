// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stands in for an external payment provider. It is deliberately slow by default and can be
 * switched to time out. {@link DemoConfiguration} keeps it out of traces, as a real third-party
 * service would be.
 */
@RestController
class FakePaymentController {

    private final DemoSettings settings;

    FakePaymentController(DemoSettings settings) {
        this.settings = settings;
    }

    @PostMapping("/fake-payment/charge")
    ResponseEntity<Map<String, String>> charge(@RequestBody Map<String, Object> payment) throws InterruptedException {
        Thread.sleep(settings.paymentDelayMillis());
        if (settings.failPayment()) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("status", "TIMEOUT"));
        }
        return ResponseEntity.ok(Map.of("status", "APPROVED"));
    }
}
