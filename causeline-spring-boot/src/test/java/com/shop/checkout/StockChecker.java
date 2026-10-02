// SPDX-License-Identifier: Apache-2.0
package com.shop.checkout;

/** Stands in for application code in tests: frames from this package count as "your code". */
public final class StockChecker {

    private StockChecker() {
    }

    public static IllegalStateException failure() {
        return new IllegalStateException("stock service timed out");
    }
}
