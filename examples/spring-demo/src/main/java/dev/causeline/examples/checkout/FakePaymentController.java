// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A harmless target for the SSRF scenario's outbound call (see SecurityScenariosController).
 * {@link DemoConfiguration} keeps it out of traces. The payment provider itself is
 * {@link FakePaymentServer}, on its own port.
 */
@RestController
class FakePaymentController {

    @GetMapping("/fake-payment/ping/**")
    Map<String, String> ping() {
        return Map.of("status", "pong");
    }
}
