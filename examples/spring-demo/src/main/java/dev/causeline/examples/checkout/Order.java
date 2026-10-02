// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    // IDENTITY makes save() run its INSERT immediately, so the SQL appears under the repository span.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String item;

    private int quantity;

    private String status;

    protected Order() {
    }

    Order(String item, int quantity) {
        this.item = item;
        this.quantity = quantity;
        this.status = "PENDING";
    }

    public Long getId() {
        return id;
    }

    public String getItem() {
        return item;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getStatus() {
        return status;
    }

    void markPaid() {
        this.status = "PAID";
    }
}
