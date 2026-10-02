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
}
