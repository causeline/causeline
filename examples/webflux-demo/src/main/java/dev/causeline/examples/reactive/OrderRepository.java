// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

interface OrderRepository extends ReactiveCrudRepository<Order, Long> {

    Flux<Order> findByItem(String item);
}
