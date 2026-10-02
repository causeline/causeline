// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.tracing;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceAssembler;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OpenTelemetry "exception" events into {@link SpanKind#EXCEPTION} point spans.
 *
 * <p>An exception that propagates through several observed layers is recorded once per layer.
 * Only the first (innermost) occurrence per trace is kept, located at the first stack frame in
 * the application's own code. The message and stack trace are kept only when explicitly enabled,
 * because they often contain user data.
 */
public final class ExceptionSpans {

    private static final AttributeKey<String> TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?:[\\w.$]+/)?([\\w.$]+)\\.([\\w$<>]+)\\(([^:)]+):(\\d+)\\)");
    private static final List<String> FRAMEWORK_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "jakarta.", "org.springframework.", "org.apache.", "org.hibernate.", "io.micrometer.", "io.opentelemetry.",
            "com.fasterxml.", "tools.jackson.", "net.ttddyy.", "org.aspectj.", "dev.causeline.spring.");
    private static final int MAX_STACKTRACE_CHARS = 8_000;
    private static final int SEEN_CAPACITY = 2_000;

    private final boolean includeDetails;
    private final List<String> appPackages;
    private final Map<String, Boolean> seen = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_CAPACITY;
        }
    };

    /**
     * @param includeDetails whether to keep exception messages and stack traces
     * @param appPackages    the application's base packages; when empty, any non-framework frame counts
     */
    public ExceptionSpans(boolean includeDetails, List<String> appPackages) {
        this.includeDetails = includeDetails;
        this.appPackages = List.copyOf(appPackages);
    }

    public synchronized List<Span> from(SpanData data, String source) {
        List<Span> spans = new ArrayList<>();
        for (EventData event : data.getEvents()) {
            if (!"exception".equals(event.getName())) {
                continue;
            }
            String type = event.getAttributes().get(TYPE);
            String stacktrace = event.getAttributes().get(STACKTRACE);
            Frame frame = appFrame(stacktrace);
            String key = data.getTraceId() + "|" + type + "|" + (frame == null ? "" : frame.location());
            if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            if (type != null) {
                attributes.put("exception.type", type);
            }
            if (frame != null) {
                attributes.put("code.location", frame.location());
                attributes.put("code.function", frame.function());
            }
            if (includeDetails) {
                putIfPresent(attributes, "exception.message", event.getAttributes().get(MESSAGE));
                putIfPresent(attributes, "exception.stacktrace", truncate(stacktrace));
            }
            spans.add(new Span(data.getTraceId(), randomSpanId(), data.getSpanId(), SpanKind.EXCEPTION,
                    type == null ? "Exception" : type.substring(type.lastIndexOf('.') + 1), source,
                    event.getEpochNanos(), 0, SpanStatus.ERROR, attributes));
        }
        return spans;
    }

    /**
     * An exception the application caught and logged, instead of letting it propagate. It is
     * recorded under the span that was active when it was logged, without failing the trace.
     */
    public Span fromLogged(String traceId, String parentSpanId, Throwable error, String level, String logger,
            String logMessage, long epochNanos, String source) {
        String type = error.getClass().getName();
        Frame frame = appFrame(error.getStackTrace());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("exception.type", type);
        attributes.put(TraceAssembler.HANDLED_ATTRIBUTE, "true");
        attributes.put("log.level", level);
        attributes.put("log.logger", logger);
        if (frame != null) {
            attributes.put("code.location", frame.location());
            attributes.put("code.function", frame.function());
        }
        if (includeDetails) {
            putIfPresent(attributes, "exception.message", error.getMessage());
            putIfPresent(attributes, "log.message", logMessage);
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            putIfPresent(attributes, "exception.stacktrace", truncate(trace.toString()));
        }
        return new Span(traceId, randomSpanId(), parentSpanId, SpanKind.EXCEPTION, error.getClass().getSimpleName(),
                source, epochNanos, 0, SpanStatus.UNSET, attributes);
    }

    Frame appFrame(StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            if (element.getLineNumber() > 0 && element.getFileName() != null && isAppClass(element.getClassName())) {
                String simple = element.getClassName().substring(element.getClassName().lastIndexOf('.') + 1);
                int nested = simple.indexOf('$');
                return new Frame(element.getFileName() + ":" + element.getLineNumber(),
                        (nested > 0 ? simple.substring(0, nested) : simple) + "." + element.getMethodName());
            }
        }
        return null;
    }

    Frame appFrame(String stacktrace) {
        if (stacktrace == null) {
            return null;
        }
        for (String line : stacktrace.split("\\R")) {
            Matcher m = FRAME.matcher(line);
            if (m.find() && isAppClass(m.group(1))) {
                String className = m.group(1);
                String simple = className.substring(className.lastIndexOf('.') + 1);
                int nested = simple.indexOf('$');
                return new Frame(m.group(3) + ":" + m.group(4),
                        (nested > 0 ? simple.substring(0, nested) : simple) + "." + m.group(2));
            }
        }
        return null;
    }

    private boolean isAppClass(String className) {
        if (className.startsWith("dev.causeline.spring.")) {
            return false;
        }
        if (!appPackages.isEmpty()) {
            return appPackages.stream().anyMatch(p -> className.startsWith(p + "."));
        }
        return FRAMEWORK_PREFIXES.stream().noneMatch(className::startsWith) && !className.contains("$$");
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_STACKTRACE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_STACKTRACE_CHARS) + "\n\t… truncated";
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String randomSpanId() {
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong();
        } while (id == 0);
        return HexFormat.of().toHexDigits(id);
    }

    record Frame(String location, String function) {
    }
}
