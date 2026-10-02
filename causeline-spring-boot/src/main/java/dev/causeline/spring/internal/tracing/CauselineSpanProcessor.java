// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hands finished spans to an exporter on a background thread. A request thread only does a
 * non-blocking {@code offer} onto a bounded queue; when the queue is full the span is dropped and
 * counted in {@link CauselineStats#serverSpansDropped}, never waited for.
 *
 * <p>Used instead of OpenTelemetry's BatchSpanProcessor because that one drops silently as far as
 * Causeline can tell.
 */
public final class CauselineSpanProcessor implements SpanProcessor {

    private static final int MAX_BATCH = 512;

    private final SpanExporter exporter;
    private final BlockingQueue<SpanData> queue;
    private final CauselineStats stats;
    private final ScheduledExecutorService worker;

    public CauselineSpanProcessor(SpanExporter exporter, int capacity, Duration delay, CauselineStats stats) {
        this.exporter = exporter;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.stats = stats;
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "causeline-span-export");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::drain, delay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (!span.getSpanContext().isSampled()) {
            return;
        }
        if (!queue.offer(span.toSpanData())) {
            stats.serverSpansDropped.incrementAndGet();
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode forceFlush() {
        drain();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        worker.shutdown();
        drain();
        return exporter.shutdown();
    }

    private synchronized void drain() {
        List<SpanData> batch = new ArrayList<>(MAX_BATCH);
        while (queue.drainTo(batch, MAX_BATCH) > 0) {
            try {
                exporter.export(batch);
            } catch (RuntimeException e) {
                // The exporter only writes to memory; a failure here must not stop the worker.
            }
            batch.clear();
        }
    }
}
