// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceLocatorTest {

    @TempDir
    Path project;

    @Test
    void findsClassesInModulesAndPointsAtTheMethod() throws Exception {
        Path file = project.resolve("orders/src/main/java/com/shop/OrderService.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.shop;

                class OrderService {
                    Order createOrder(String item) {
                        return orders.save(createDraft(item));
                    }

                    private Order createDraft(String item) {
                        return new Order(item);
                    }
                }
                """);
        Files.createDirectories(project.resolve("node_modules/x/src/main/java/com/shop"));
        SourceLocator locator = new SourceLocator(project);

        assertThat(locator.locate("com.shop.OrderService", "createOrder", 0))
                .hasValueSatisfying(l -> {
                    assertThat(l.path()).isEqualTo(file.toString());
                    assertThat(l.line()).isEqualTo(4);
                });
        assertThat(locator.locate("com.shop.OrderService", "createDraft", 0)).get().extracting(SourceLocator.Location::line)
                .isEqualTo(8);
        assertThat(locator.locate("com.shop.OrderService$Inner", null, 42)).get().extracting(SourceLocator.Location::line)
                .isEqualTo(42);
        assertThat(locator.locate("com.shop.Missing", null, 0)).isEmpty();
        assertThat(locator.locate("../../etc/passwd", null, 0)).isEmpty();
    }
}
