// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** No index on {@code item}: the query scans the table, which Causeline's EXPLAIN points out. */
    List<Order> findByItem(String item);
}
