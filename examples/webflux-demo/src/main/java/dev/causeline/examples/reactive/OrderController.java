// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Checkout API used to demonstrate Causeline in a WebFlux application. */
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
    Mono<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        return orders.createOrder(request.item(), Math.max(1, request.quantity()))
                .map(order -> new OrderResponse(order.id(), order.status()));
    }

    @GetMapping
    Flux<Order> ordersFor(@RequestParam("item") String item) {
        return orders.ordersFor(item);
    }
}
