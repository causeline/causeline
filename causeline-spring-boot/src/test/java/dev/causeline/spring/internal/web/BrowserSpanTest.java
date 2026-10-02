// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowserSpanTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    @Test
    void convertsWithBrowserSourceAndLargeStartTime() {
        Span span = new BrowserSpan(TRACE, "00f067aa0ba902b7", null, SpanKind.UI_ACTION, "Checkout",
                "1790612345123456000", 876_000_000, SpanStatus.OK, Map.of()).toSpan();

        assertThat(span.source()).isEqualTo("browser");
        assertThat(span.startTimeUnixNano()).isEqualTo(1_790_612_345_123_456_000L);
    }

    @Test
    void rejectsServerSideKinds() {
        BrowserSpan forged = new BrowserSpan(TRACE, "00f067aa0ba902b7", null, SpanKind.DATABASE, "DROP users",
                "1", 1, SpanStatus.OK, Map.of());

        assertThatIllegalArgumentException().isThrownBy(forged::toSpan).withMessageContaining("DATABASE");
    }

    @Test
    void rejectsNonNumericStartTime() {
        BrowserSpan bad = new BrowserSpan(TRACE, "00f067aa0ba902b7", null, SpanKind.REQUEST, "GET /",
                "yesterday", 1, SpanStatus.OK, Map.of());

        assertThatIllegalArgumentException().isThrownBy(bad::toSpan);
    }

    @Test
    void recognisesOnlyCauselinePaths() {
        assertThat(CauselineWebConfiguration.isCauselinePath("/causeline")).isTrue();
        assertThat(CauselineWebConfiguration.isCauselinePath("/causeline/api/traces")).isTrue();
        assertThat(CauselineWebConfiguration.isCauselinePath("/causelineish")).isFalse();
        assertThat(CauselineWebConfiguration.isCauselinePath("/api/orders")).isFalse();
    }

    @Test
    void ignoresBrowserHousekeepingRequests() {
        assertThat(CauselineWebConfiguration.isIgnoredPath("/favicon.ico")).isTrue();
        assertThat(CauselineWebConfiguration.isIgnoredPath("/.well-known/appspecific/com.chrome.devtools.json")).isTrue();
        assertThat(CauselineWebConfiguration.isIgnoredPath("/api/orders")).isFalse();
    }
}
