// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import dev.causeline.core.TraceStore;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Logback appender that records exceptions the application caught and logged (WARN or ERROR, with
 * an exception attached) while a traced request was running. Everything else is ignored at the
 * cost of two field reads, so it adds nothing noticeable to normal logging.
 */
public final class LoggedExceptionAppender extends AppenderBase<ILoggingEvent> {

    /** The span active on the logging thread, or {@code null} when nothing is being traced. */
    public record ActiveSpan(String traceId, String spanId) {
    }

    private static final ThreadLocal<Boolean> RECORDING = ThreadLocal.withInitial(() -> false);

    private final Supplier<ActiveSpan> activeSpan;
    private final ExceptionSpans exceptions;
    private final TraceStore store;
    private final String source;

    public LoggedExceptionAppender(Supplier<ActiveSpan> activeSpan, ExceptionSpans exceptions, TraceStore store,
            String source) {
        this.activeSpan = activeSpan;
        this.exceptions = exceptions;
        this.store = store;
        this.source = source;
        setName("causeline-logged-exceptions");
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN) || !(event.getThrowableProxy() instanceof ThrowableProxy proxy)
                || event.getLoggerName().startsWith("dev.causeline.spring.")) { // Causeline's own logs only
            return;
        }
        if (RECORDING.get()) {
            return; // something logged while we were recording; never recurse
        }
        RECORDING.set(true);
        try {
            ActiveSpan span = activeSpan.get();
            if (span == null) {
                return;
            }
            Instant at = event.getInstant();
            store.add(exceptions.fromLogged(span.traceId(), span.spanId(), proxy.getThrowable(), event.getLevel().toString(),
                    event.getLoggerName(), event.getFormattedMessage(),
                    at.getEpochSecond() * 1_000_000_000L + at.getNano(), source));
        } catch (RuntimeException e) {
            // Debug tooling must never break the application's logging.
        } finally {
            RECORDING.set(false);
        }
    }
}
