// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("orders")
record Order(@Id Long id, String item, int quantity, String status) {

    static Order pending(String item, int quantity) {
        return new Order(null, item, quantity, "PENDING");
    }

    Order paid() {
        return new Order(id, item, quantity, "PAID");
    }
}
