// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class CauselineSpanProcessorTest {

    private final List<SpanData> exported = new ArrayList<>();
    private final SpanExporter exporter = new SpanExporter() {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exported.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    };

    @Test
    void countsSpansDroppedWhenTheQueueIsFullInsteadOfBlocking() {
        CauselineStats stats = new CauselineStats();
        // A long delay keeps the worker from draining during the test.
        CauselineSpanProcessor processor = new CauselineSpanProcessor(exporter, 2, Duration.ofHours(1), stats);
        Tracer tracer = SdkTracerProvider.builder().addSpanProcessor(processor).build().get("test");

        for (int i = 0; i < 5; i++) {
            tracer.spanBuilder("op-" + i).startSpan().end();
        }
        processor.forceFlush();

        assertThat(exported).extracting(SpanData::getName).containsExactly("op-0", "op-1");
        assertThat(stats.serverSpansDropped.get()).isEqualTo(3);
    }
}
