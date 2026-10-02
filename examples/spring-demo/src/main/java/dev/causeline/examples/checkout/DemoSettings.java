// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Switches the demo page flips to show a slow, a failing, and a quietly degraded checkout. */
@Component
class DemoSettings {

    private volatile boolean slowPayment = true;
    private volatile boolean failPayment;
    private volatile boolean flakyStock;
    private final long slowDelayMillis;
    private final long fastDelayMillis;

    DemoSettings(@Value("${demo.payment.delay-ms:800}") long slowDelayMillis,
            @Value("${demo.payment.fast-delay-ms:30}") long fastDelayMillis) {
        this.slowDelayMillis = slowDelayMillis;
        this.fastDelayMillis = fastDelayMillis;
    }

    record Snapshot(boolean slowPayment, boolean failPayment, boolean flakyStock) {
    }

    Snapshot snapshot() {
        return new Snapshot(slowPayment, failPayment, flakyStock);
    }

    void update(Snapshot next) {
        slowPayment = next.slowPayment();
        failPayment = next.failPayment();
        flakyStock = next.flakyStock();
    }

    boolean failPayment() {
        return failPayment;
    }

    boolean flakyStock() {
        return flakyStock;
    }

    long paymentDelayMillis() {
        return slowPayment ? slowDelayMillis : fastDelayMillis;
    }
}
