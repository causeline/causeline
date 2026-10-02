// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.export;

import dev.causeline.core.Span;
import java.util.Collection;

/** Receives every span Causeline stores, for optional export elsewhere. Must never block. */
@FunctionalInterface
public interface SpanForwarder {

    SpanForwarder NONE = spans -> {
    };

    void forward(Collection<Span> spans);

    /** One forwarder that hands the same spans to each of these, e.g. OTLP and the upstream Causeline. */
    static SpanForwarder all(java.util.List<? extends SpanForwarder> forwarders) {
        java.util.List<SpanForwarder> list = java.util.List.copyOf(forwarders);
        if (list.isEmpty()) {
            return NONE;
        }
        // Always a new object, never one of the forwarders: as a bean it must not look like a second OTLP
        // or upstream forwarder to type-based injection.
        return spans -> list.forEach(f -> f.forward(spans));
    }
}
