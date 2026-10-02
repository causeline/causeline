// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.shop.checkout.StockChecker;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoggedExceptionAppenderTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN = "00f067aa0ba902b7";

    private final TraceStore store = new TraceStore();
    private final AtomicReference<LoggedExceptionAppender.ActiveSpan> active =
            new AtomicReference<>(new LoggedExceptionAppender.ActiveSpan(TRACE, SPAN));
    private final LoggerContext context = new LoggerContext();
    private final Logger logger = context.getLogger("com.shop.checkout.StockChecker");

    private LoggedExceptionAppender appender(boolean details) {
        LoggedExceptionAppender appender = new LoggedExceptionAppender(active::get,
                new ExceptionSpans(details, List.of("com.shop")), store, "checkout");
        appender.setContext(context);
        appender.start();
        return appender;
    }

    @Test
    void recordsALoggedExceptionUnderTheActiveSpanWithoutFailingIt() {
        appender(false).doAppend(event(Level.WARN, "Stock service down for alice@example.com", stockFailure()));

        List<Span> spans = store.get(TRACE).orElseThrow();
        assertThat(spans).singleElement().satisfies(span -> {
            assertThat(span.kind()).isEqualTo(SpanKind.EXCEPTION);
            assertThat(span.parentSpanId()).isEqualTo(SPAN);
            assertThat(span.status()).isEqualTo(SpanStatus.UNSET);
            assertThat(span.name()).isEqualTo("IllegalStateException");
            assertThat(span.attributes())
                    .containsEntry(TraceAssembler.HANDLED_ATTRIBUTE, "true")
                    .containsEntry("log.level", "WARN")
                    .containsEntry("log.logger", "com.shop.checkout.StockChecker")
                    .containsEntry("code.function", "StockChecker.failure")
                    .doesNotContainKeys("exception.message", "log.message", "exception.stacktrace");
            assertThat(span.attributes().get("code.location")).startsWith("StockChecker.java:");
        });
        assertThat(spans.toString()).doesNotContain("alice@example.com");
    }

    @Test
    void keepsMessagesOnlyWhenDetailsAreEnabled() {
        appender(true).doAppend(event(Level.ERROR, "Stock service down", stockFailure()));

        assertThat(store.get(TRACE).orElseThrow().getFirst().attributes())
                .containsEntry("log.message", "Stock service down")
                .containsKeys("exception.message", "exception.stacktrace");
    }

    @Test
    void ignoresInfoLevelLogsWithoutExceptionsAndUntracedThreads() {
        LoggedExceptionAppender appender = appender(false);
        appender.doAppend(event(Level.INFO, "retrying", stockFailure()));
        appender.doAppend(event(Level.WARN, "no exception attached", null));
        active.set(null);
        appender.doAppend(event(Level.ERROR, "outside any request", stockFailure()));

        assertThat(store.size()).isZero();
    }

    @Test
    void skipsCauselinesOwnLoggersButNotAppsWithASimilarPackage() {
        LoggedExceptionAppender appender = appender(false);
        appender.doAppend(new LoggingEvent(Logger.class.getName(), context.getLogger("dev.causeline.spring.internal.web.X"),
                Level.WARN, "internal", stockFailure(), null));
        assertThat(store.size()).isZero();

        appender.doAppend(new LoggingEvent(Logger.class.getName(),
                context.getLogger("dev.causeline.examples.checkout.StockService"), Level.WARN, "app", stockFailure(), null));
        assertThat(store.size()).isEqualTo(1);
    }

    private LoggingEvent event(Level level, String message, Throwable error) {
        return new LoggingEvent(Logger.class.getName(), logger, level, message, error, null);
    }

    private static IllegalStateException stockFailure() {
        return StockChecker.failure();
    }
}
