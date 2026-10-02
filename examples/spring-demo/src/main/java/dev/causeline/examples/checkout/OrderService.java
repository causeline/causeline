// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

@Service
class OrderService {

    private final OrderRepository orders;
    private final PaymentClient payments;
    private final StockService stock;
    private final ConfirmationMailer mailer;

    OrderService(OrderRepository orders, PaymentClient payments, StockService stock, ConfirmationMailer mailer) {
        this.orders = orders;
        this.payments = payments;
        this.stock = stock;
        this.mailer = mailer;
    }

    /** {@code @Observed} is all it takes for this method to appear as a SERVICE span in Causeline. */
    @Observed
    public Order createOrder(String item, int quantity) {
        if (!stock.isInStock(item)) {
            throw new IllegalStateException("Out of stock: " + item);
        }
        Order order = orders.save(new Order(item, quantity));
        payments.charge(order.getId(), quantity);
        order.markPaid();
        Order paid = orders.save(order);
        mailer.sendConfirmation(paid.getId()); // async: returns at once
        return paid;
    }
}
