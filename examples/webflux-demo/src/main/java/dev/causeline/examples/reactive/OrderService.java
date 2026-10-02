// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
class OrderService {

    private final OrderRepository orders;
    private final PaymentClient payments;

    OrderService(OrderRepository orders, PaymentClient payments) {
        this.orders = orders;
        this.payments = payments;
    }

    /** Returns at once; the span lasts until the order is saved, charged and saved again. */
    @Observed
    public Mono<Order> createOrder(String item, int quantity) {
        return orders.save(Order.pending(item, quantity))
                .flatMap(order -> payments.charge(order.id(), quantity).thenReturn(order))
                .flatMap(order -> orders.save(order.paid()));
    }

    @Observed
    public Flux<Order> ordersFor(String item) {
        return orders.findByItem(item);
    }
}
