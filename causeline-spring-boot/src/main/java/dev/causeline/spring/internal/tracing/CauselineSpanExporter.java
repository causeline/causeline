// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import dev.causeline.spring.internal.export.SpanForwarder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes finished spans into the in-memory {@link TraceStore}. It runs on the batch processor's
 * worker thread, never on a request thread.
 */
public final class CauselineSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(CauselineSpanExporter.class);

    private final SpanMapper mapper;
    private final ExceptionSpans exceptions;
    private final TraceStore store;
    private final SpanForwarder forwarder;

    public CauselineSpanExporter(SpanMapper mapper, ExceptionSpans exceptions, TraceStore store) {
        this(mapper, exceptions, store, SpanForwarder.NONE);
    }

    /** @param forwarder receives exactly the spans that were stored, e.g. for OTLP export */
    public CauselineSpanExporter(SpanMapper mapper, ExceptionSpans exceptions, TraceStore store, SpanForwarder forwarder) {
        this.mapper = mapper;
        this.exceptions = exceptions;
        this.store = store;
        this.forwarder = forwarder;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        List<Span> stored = new ArrayList<>();
        for (SpanData data : spans) {
            try {
                Span span = mapper.map(data);
                // SQL outside any request (schema creation at startup, pool checks) is not part of a
                // user action and would only clutter the trace list.
                if (span.kind() == SpanKind.DATABASE && !span.hasParent()) {
                    continue;
                }
                stored.add(span);
                stored.addAll(exceptions.from(data, span.source()));
            } catch (RuntimeException e) {
                // One malformed span must not lose the rest of the batch.
                log.debug("Causeline skipped span {}: {}", data.getName(), e.getMessage());
            }
        }
        store.addAll(stored);
        forwarder.forward(stored);
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
}
