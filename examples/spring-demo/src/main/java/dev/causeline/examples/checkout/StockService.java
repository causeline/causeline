// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Checks stock before an order is placed. When the (simulated) inventory service is flaky, the
 * failure is caught and logged, and checkout carries on: the kind of handled exception that
 * makes a request slow without failing it.
 */
@Service
class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final DemoSettings settings;

    StockService(DemoSettings settings) {
        this.settings = settings;
    }

    @Observed
    public boolean isInStock(String item) {
        try {
            return askInventory(item);
        } catch (InventoryUnavailableException e) {
            log.warn("Inventory service unavailable, assuming '{}' is in stock", item, e);
            return true;
        }
    }

    private boolean askInventory(String item) {
        if (settings.flakyStock()) {
            sleep(150);
            throw new InventoryUnavailableException("Inventory service did not answer within 150 ms");
        }
        return true;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class InventoryUnavailableException extends RuntimeException {
        InventoryUnavailableException(String message) {
            super(message);
        }
    }
}
