// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

class PaymentTimeoutException extends RuntimeException {

    PaymentTimeoutException(long orderId, Throwable cause) {
        super("Payment provider timed out for order " + orderId, cause);
    }
}
