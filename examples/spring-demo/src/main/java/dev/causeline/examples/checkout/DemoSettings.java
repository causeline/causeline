// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Switches the demo page flips to show a slow, a failing, and a quietly degraded checkout, and a
 * payment client that lets only one call through at a time (what a load replay finds).
 */
@Component
class DemoSettings {

    private volatile boolean slowPayment = true;
    private volatile boolean failPayment;
    private volatile boolean flakyStock;
    private volatile boolean contendedPayment;
    private final long slowDelayMillis;
    private final long fastDelayMillis;

    DemoSettings(@Value("${demo.payment.delay-ms:800}") long slowDelayMillis,
            @Value("${demo.payment.fast-delay-ms:30}") long fastDelayMillis) {
        this.slowDelayMillis = slowDelayMillis;
        this.fastDelayMillis = fastDelayMillis;
    }

    /** @param contendedPayment optional, so clients that predate the switch can still send the other three */
    record Snapshot(boolean slowPayment, boolean failPayment, boolean flakyStock, Boolean contendedPayment) {

        Snapshot(boolean slowPayment, boolean failPayment, boolean flakyStock) {
            this(slowPayment, failPayment, flakyStock, false);
        }
    }

    Snapshot snapshot() {
        return new Snapshot(slowPayment, failPayment, flakyStock, contendedPayment);
    }

    void update(Snapshot next) {
        slowPayment = next.slowPayment();
        failPayment = next.failPayment();
        flakyStock = next.flakyStock();
        contendedPayment = Boolean.TRUE.equals(next.contendedPayment());
    }

    boolean failPayment() {
        return failPayment;
    }

    boolean flakyStock() {
        return flakyStock;
    }

    boolean contendedPayment() {
        return contendedPayment;
    }

    long paymentDelayMillis() {
        return slowPayment ? slowDelayMillis : fastDelayMillis;
    }
}
