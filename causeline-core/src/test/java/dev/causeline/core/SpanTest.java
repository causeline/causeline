// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    void rejectsIdsThatAreNotW3cHex() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> span("8f72a91", SPAN, null))
                .withMessageContaining("traceId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> span(TRACE, "00F067AA0BA902B7", null))
                .withMessageContaining("spanId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> span(TRACE, SPAN, "abc"))
                .withMessageContaining("parentSpanId");
    }

    @Test
    void attributesAreCopiedDefensively() {
        Map<String, String> attributes = new HashMap<>(Map.of("http.request.method", "POST"));
        Span span = new Span(TRACE, SPAN, null, SpanKind.REQUEST, "POST /api/orders", "browser",
                0, 10, SpanStatus.OK, attributes);

        attributes.put("injected", "later");

        assertThat(span.attributes()).containsOnlyKeys("http.request.method");
        assertThat(span.hasParent()).isFalse();
        assertThat(span.endTimeUnixNano()).isEqualTo(10);
    }

    private static Span span(String traceId, String spanId, String parentSpanId) {
        return new Span(traceId, spanId, parentSpanId, SpanKind.SERVICE, "op", "test", 0, 1,
                SpanStatus.OK, null);
    }
}
