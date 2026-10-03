// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import java.util.function.Supplier;

/**
 * Looks a bean up the first time it is there, then keeps it. Post-processors receive beans this
 * way because they must not create them early, but looking one up by type on every cache call or
 * transaction would cost more than the span itself.
 */
final class Once<T> implements Supplier<T> {

    private final Supplier<T> lookup;
    private volatile T value;

    private Once(Supplier<T> lookup) {
        this.lookup = lookup;
    }

    static <T> Supplier<T> of(Supplier<T> lookup) {
        return new Once<>(lookup);
    }

    @Override
    public T get() {
        T current = value;
        if (current == null) {
            current = lookup.get();
            value = current; // stays null until the bean exists
        }
        return current;
    }
}
