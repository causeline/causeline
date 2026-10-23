// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** Orders for one item, e.g. {@code GET /api/orders?item=book}. */
    @GetMapping
    List<OrderResponse> ordersFor(@RequestParam("item") String item) {
        return orders.ordersFor(item).stream().map(o -> new OrderResponse(o.getId(), o.getStatus())).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        Order order = orders.createOrder(request.item(), Math.max(1, request.quantity()));
        return new OrderResponse(order.getId(), order.getStatus());
    }
}
