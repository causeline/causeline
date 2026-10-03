// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final PaymentClient payments;
    private final StockService stock;
    private final ConfirmationMailer mailer;
    private final PriceCatalog prices;

    OrderService(OrderRepository orders, PaymentClient payments, StockService stock, ConfirmationMailer mailer,
            PriceCatalog prices) {
        this.orders = orders;
        this.payments = payments;
        this.stock = stock;
        this.mailer = mailer;
        this.prices = prices;
    }

    /** {@code @Observed} is all it takes for this method to appear as a SERVICE span in Causeline. */
    @Observed
    @Transactional
    public Order createOrder(String item, int quantity) {
        if (!stock.isInStock(item)) {
            throw new IllegalStateException("Out of stock: " + item);
        }
        int price = prices.priceOf(item);
        Order order = orders.save(new Order(item, quantity));
        payments.charge(order.getId(), quantity);
        order.markPaid();
        Order paid = orders.save(order);
        log.info("Order {} paid: {} x {} at {} cents", paid.getId(), quantity, item, price);
        mailer.sendConfirmation(paid.getId()); // async: returns at once
        return paid;
    }
}
