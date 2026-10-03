// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Prices rarely change, so they are cached: the first checkout of an item misses, later ones hit. */
@Service
class PriceCatalog {

    @Cacheable("prices")
    public int priceOf(String item) {
        return 1200;
    }
}
