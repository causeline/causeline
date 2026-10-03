// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties.LogCapture;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Logback appender that puts what the application logs during a traced request on its timeline:
 * <ul>
 *   <li>exceptions it caught and logged (WARN or ERROR, with an exception attached) become
 *       {@link SpanKind#EXCEPTION} spans;</li>
 *   <li>other log lines at {@code causeline.capture.logs} and above become {@link SpanKind#LOG}
 *       point spans under the span that was active, up to {@value #MAX_LOGS_PER_TRACE} per trace.</li>
 * </ul>
 * Events outside a traced request are ignored at the cost of a level check and a lookup.
 */
public final class LoggedExceptionAppender extends AppenderBase<ILoggingEvent> {

    /** The span active on the logging thread, or {@code null} when nothing is being traced. */
    public record ActiveSpan(String traceId, String spanId) {
    }

    static final int MAX_LOGS_PER_TRACE = 200;
    static final int MAX_MESSAGE_CHARS = 2_000;
    private static final int TRACKED_TRACES = 1_000;

    private static final ThreadLocal<Boolean> RECORDING = ThreadLocal.withInitial(() -> false);

    private final Supplier<ActiveSpan> activeSpan;
    private final ExceptionSpans exceptions;
    private final TraceStore store;
    private final String source;
    private final Level logLevel;
    private final Map<String, Integer> logsPerTrace = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > TRACKED_TRACES;
        }
    };

    public LoggedExceptionAppender(Supplier<ActiveSpan> activeSpan, ExceptionSpans exceptions, TraceStore store,
            String source) {
        this(activeSpan, exceptions, store, source, LogCapture.OFF);
    }

    /** @param logs which log lines to record besides logged exceptions */
    public LoggedExceptionAppender(Supplier<ActiveSpan> activeSpan, ExceptionSpans exceptions, TraceStore store,
            String source, LogCapture logs) {
        this.activeSpan = activeSpan;
        this.exceptions = exceptions;
        this.store = store;
        this.source = source;
        this.logLevel = switch (logs) {
            case OFF -> Level.OFF;
            case ERROR -> Level.ERROR;
            case WARN -> Level.WARN;
            case INFO -> Level.INFO;
            case DEBUG -> Level.DEBUG;
        };
        setName("causeline-logs");
    }

    @Override
    protected void append(ILoggingEvent event) {
        Level level = event.getLevel();
        boolean exception = level.isGreaterOrEqual(Level.WARN) && event.getThrowableProxy() instanceof ThrowableProxy;
        boolean log = logLevel != Level.OFF && level.isGreaterOrEqual(logLevel);
        if ((!exception && !log) || event.getLoggerName().startsWith("dev.causeline.spring.")) { // never our own logs
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
            long epochNanos = at.getEpochSecond() * 1_000_000_000L + at.getNano();
            if (exception) {
                ThrowableProxy proxy = (ThrowableProxy) event.getThrowableProxy();
                store.add(exceptions.fromLogged(span.traceId(), span.spanId(), proxy.getThrowable(), level.toString(),
                        event.getLoggerName(), event.getFormattedMessage(), epochNanos, source));
            } else if (underLimit(span.traceId())) {
                store.add(logSpan(span, event, epochNanos));
            }
        } catch (RuntimeException e) {
            // Debug tooling must never break the application's logging.
        } finally {
            RECORDING.set(false);
        }
    }

    private synchronized boolean underLimit(String traceId) {
        int count = logsPerTrace.merge(traceId, 1, Integer::sum);
        return count <= MAX_LOGS_PER_TRACE;
    }

    private Span logSpan(ActiveSpan parent, ILoggingEvent event, long epochNanos) {
        String message = String.valueOf(event.getFormattedMessage());
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS) + "…";
        }
        String logger = event.getLoggerName();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("log.level", event.getLevel().toString());
        attributes.put("log.logger", logger);
        attributes.put("log.message", message);
        attributes.put("thread.name", event.getThreadName());
        String shortLogger = logger.substring(logger.lastIndexOf('.') + 1);
        String name = event.getLevel() + " " + shortLogger + ": " + firstLine(message);
        return new Span(parent.traceId(), randomSpanId(), parent.spanId(), SpanKind.LOG, name, source, epochNanos, 0,
                event.getLevel().isGreaterOrEqual(Level.ERROR) ? SpanStatus.UNSET : SpanStatus.OK, attributes);
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() <= 120 ? line : line.substring(0, 120) + "…";
    }

    private static String randomSpanId() {
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong();
        } while (id == 0);
        return HexFormat.of().toHexDigits(id);
    }
}
