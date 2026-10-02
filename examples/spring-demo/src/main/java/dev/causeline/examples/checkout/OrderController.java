// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Checkout API used to demonstrate Causeline. */
@RestController
@RequestMapping("/api/orders")
class OrderController {

    record CreateOrderRequest(String item, int quantity) {
    }

    record OrderResponse(long orderId, String status) {
    }

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        Order order = orders.createOrder(request.item(), Math.max(1, request.quantity()));
        return new OrderResponse(order.getId(), order.getStatus());
    }
}
