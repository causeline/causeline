// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionSpansTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String STACK = """
            com.shop.checkout.PaymentTimeoutException: card 4111-1111 declined for alice@example.com
            \tat com.shop.checkout.PaymentClient.charge(PaymentClient.java:42)
            \tat com.shop.checkout.PaymentClient$$SpringCGLIB$$0.charge(<generated>)
            \tat org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184)
            \tat com.shop.checkout.OrderService.createOrder(OrderService.java:23)
            """;

    @Test
    void locatesTheFirstFrameInApplicationCodeAndHidesDetailsByDefault() {
        ExceptionSpans exceptions = new ExceptionSpans(false, List.of("com.shop"));

        List<Span> spans = exceptions.from(spanWithException("b7ad6b7169203331"), "checkout");

        assertThat(spans).singleElement().satisfies(span -> {
            assertThat(span.kind()).isEqualTo(SpanKind.EXCEPTION);
            assertThat(span.name()).isEqualTo("PaymentTimeoutException");
            assertThat(span.parentSpanId()).isEqualTo("b7ad6b7169203331");
            assertThat(span.status()).isEqualTo(SpanStatus.ERROR);
            assertThat(span.durationNanos()).isZero();
            assertThat(span.attributes())
                    .containsEntry("exception.type", "com.shop.checkout.PaymentTimeoutException")
                    .containsEntry("code.location", "PaymentClient.java:42")
                    .containsEntry("code.function", "PaymentClient.charge")
                    .doesNotContainKeys("exception.message", "exception.stacktrace");
            assertThat(span.attributes().values()).noneMatch(v -> v.contains("alice@example.com"));
        });
    }

    @Test
    void keepsMessageAndStackOnlyWhenEnabled() {
        ExceptionSpans exceptions = new ExceptionSpans(true, List.of("com.shop"));

        Span span = exceptions.from(spanWithException("b7ad6b7169203331"), "checkout").getFirst();

        assertThat(span.attributes()).containsKeys("exception.message", "exception.stacktrace");
    }

    @Test
    void recordsAnExceptionOncePerTraceEvenWhenEveryLayerReportsIt() {
        ExceptionSpans exceptions = new ExceptionSpans(false, List.of("com.shop"));

        List<Span> inner = exceptions.from(spanWithException("b7ad6b7169203331"), "checkout");
        List<Span> outer = exceptions.from(spanWithException("c7ad6b7169203331"), "checkout");

        assertThat(inner).hasSize(1);
        assertThat(outer).isEmpty();
    }

    @Test
    void fallsBackToFirstNonFrameworkFrameWithoutAppPackages() {
        ExceptionSpans exceptions = new ExceptionSpans(false, List.of());

        assertThat(exceptions.appFrame(STACK)).isEqualTo(new ExceptionSpans.Frame("PaymentClient.java:42", "PaymentClient.charge",
                "com.shop.checkout.PaymentClient", 42));
    }

    private static SpanData spanWithException(String spanId) {
        Attributes event = Attributes.builder()
                .put("exception.type", "com.shop.checkout.PaymentTimeoutException")
                .put("exception.message", "card 4111-1111 declined for alice@example.com")
                .put("exception.stacktrace", STACK)
                .build();
        return TestSpanData.builder()
                .setSpanContext(SpanContext.create(TRACE, spanId, TraceFlags.getSampled(), TraceState.getDefault()))
                .setName("PaymentClient.charge")
                .setKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setStartEpochNanos(1)
                .setEndEpochNanos(10)
                .setEvents(List.of(EventData.create(5, "exception", event)))
                .setStatus(StatusData.error())
                .setHasEnded(true)
                .setTotalRecordedEvents(1)
                .setTotalRecordedLinks(0)
                .setTotalAttributeCount(0)
                .build();
    }
}
