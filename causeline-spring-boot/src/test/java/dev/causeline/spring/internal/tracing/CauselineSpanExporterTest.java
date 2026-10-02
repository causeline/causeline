// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.TraceStore;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import org.junit.jupiter.api.Test;

class CauselineSpanExporterTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";

    private final TraceStore store = new TraceStore();
    private final CauselineSpanExporter exporter =
            new CauselineSpanExporter(new SpanMapper("app"), new ExceptionSpans(false, List.of()), store);

    @Test
    void dropsSqlThatIsNotPartOfARequest() {
        exporter.export(List.of(span(null, Attributes.builder().put("jdbc.query[0]", "create table orders").build())));

        assertThat(store.size()).isZero();
    }

    @Test
    void keepsSqlInsideARequest() {
        exporter.export(List.of(span("00f067aa0ba902b7",
                Attributes.builder().put("jdbc.query[0]", "select * from orders").build())));

        assertThat(store.get(TRACE)).isPresent();
    }

    private static SpanData span(String parentSpanId, Attributes attributes) {
        return TestSpanData.builder()
                .setSpanContext(SpanContext.create(TRACE, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault()))
                .setParentSpanContext(parentSpanId == null
                        ? SpanContext.getInvalid()
                        : SpanContext.create(TRACE, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault()))
                .setName("query")
                .setKind(SpanKind.CLIENT)
                .setStartEpochNanos(1)
                .setEndEpochNanos(2)
                .setAttributes(attributes)
                .setStatus(StatusData.unset())
                .setHasEnded(true)
                .setTotalRecordedEvents(0)
                .setTotalRecordedLinks(0)
                .setTotalAttributeCount(attributes.size())
                .build();
    }
}
