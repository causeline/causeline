// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.annotation.Observed;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends the order confirmation on a background thread, as real shops do. With Causeline the
 * {@code @Async} work still shows up in the checkout's trace rather than as a separate one.
 */
@Service
class ConfirmationMailer {

    @Async
    @Observed
    public void sendConfirmation(long orderId) {
        try {
            Thread.sleep(25); // stands in for an SMTP round trip
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
