// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.export;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.spring.autoconfigure.CauselineProperties.Otlp;
import dev.causeline.spring.internal.capture.SpanRedactor;
import dev.causeline.spring.internal.tracing.CauselineStats;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends Causeline spans to an OTLP/HTTP endpoint (Jaeger, Grafana Tempo, an OpenTelemetry
 * collector, the Datadog agent) as OTLP JSON.
 *
 * <p>What is exported is what Causeline stored, never Spring's raw spans, passed through the
 * export {@link SpanRedactor} so credentials stay in this application unless
 * {@code causeline.export.redact-secrets=false}. Spans are queued without blocking and sent from a background thread; when
 * the queue is full or the endpoint fails, spans are dropped and counted, never retried forever.
 */
public final class OtlpForwarder implements SpanForwarder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtlpForwarder.class);
    private static final int QUEUE_CAPACITY = 4_096;
    private static final int MAX_BATCH = 512;

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final String appName;
    private final CauselineStats stats;
    private final SpanRedactor redactor;
    private final BlockingQueue<Span> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final HttpClient http;
    private final ScheduledExecutorService worker;
    private volatile boolean warned;

    public OtlpForwarder(Otlp config, String appName, CauselineStats stats, Duration interval) {
        this(config, appName, stats, interval, SpanRedactor.NONE);
    }

    /** @param redactor applied just before sending, so secrets stay in this application */
    public OtlpForwarder(Otlp config, String appName, CauselineStats stats, Duration interval, SpanRedactor redactor) {
        this.redactor = redactor;
        this.endpoint = URI.create(config.endpoint());
        this.headers = Map.copyOf(config.headers());
        this.timeout = config.timeout();
        this.appName = appName;
        this.stats = stats;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "causeline-otlp-export");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::flush, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void forward(Collection<Span> spans) {
        for (Span span : spans) {
            if (!queue.offer(span)) {
                stats.otlpDropped.incrementAndGet();
            }
        }
    }

    /** Sends everything queued so far. Runs on the export thread; also used by tests and on shutdown. */
    public synchronized void flush() {
        List<Span> batch = new ArrayList<>(MAX_BATCH);
        while (queue.drainTo(batch, MAX_BATCH) > 0) {
            send(batch);
            batch.clear();
        }
    }

    @Override
    public void close() {
        worker.shutdown();
        flush();
    }

    private void send(List<Span> batch) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OtlpJson.encode(batch.stream().map(redactor::redact).toList(), appName)));
        headers.forEach(request::header);
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                stats.otlpExported.addAndGet(batch.size());
                return;
            }
            failed(batch, "HTTP " + response.statusCode());
        } catch (IOException e) {
            failed(batch, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(batch, "interrupted");
        }
    }

    private void failed(List<Span> batch, String reason) {
        stats.otlpFailedRequests.incrementAndGet();
        stats.otlpDropped.addAndGet(batch.size());
        if (!warned) {
            warned = true; // once is enough: the status API keeps counting
            log.warn("Causeline could not export {} spans to {}: {}. Further failures are counted, not logged.",
                    batch.size(), endpoint.getHost(), reason);
        }
    }

    /** OTLP/HTTP JSON encoding (opentelemetry-proto, JSON mapping). Written by hand to avoid a dependency. */
    static final class OtlpJson {

        private OtlpJson() {
        }

        static String encode(List<Span> spans, String appName) {
            // One resource per source: the Spring app, and "<app>-browser" for browser spans.
            Map<String, List<Span>> bySource = new LinkedHashMap<>();
            spans.forEach(s -> bySource.computeIfAbsent(s.source(), k -> new ArrayList<>()).add(s));
            StringBuilder json = new StringBuilder("{\"resourceSpans\":[");
            boolean firstResource = true;
            for (Map.Entry<String, List<Span>> entry : bySource.entrySet()) {
                String service = "browser".equals(entry.getKey()) ? appName + "-browser" : entry.getKey();
                json.append(firstResource ? "" : ",")
                        .append("{\"resource\":{\"attributes\":[").append(attribute("service.name", service))
                        .append("]},\"scopeSpans\":[{\"scope\":{\"name\":\"dev.causeline\"},\"spans\":[");
                boolean firstSpan = true;
                for (Span span : entry.getValue()) {
                    json.append(firstSpan ? "" : ",").append(span(span));
                    firstSpan = false;
                }
                json.append("]}]}");
                firstResource = false;
            }
            return json.append("]}").toString();
        }

        private static String span(Span span) {
            StringBuilder json = new StringBuilder("{")
                    .append("\"traceId\":\"").append(span.traceId()).append("\",")
                    .append("\"spanId\":\"").append(span.spanId()).append("\",");
            if (span.parentSpanId() != null) {
                json.append("\"parentSpanId\":\"").append(span.parentSpanId()).append("\",");
            }
            json.append("\"name\":").append(string(span.name())).append(',')
                    .append("\"kind\":").append(kind(span)).append(',')
                    .append("\"startTimeUnixNano\":\"").append(span.startTimeUnixNano()).append("\",")
                    .append("\"endTimeUnixNano\":\"").append(span.endTimeUnixNano()).append("\",")
                    .append("\"attributes\":[").append(attribute("causeline.kind", span.kind().name()));
            span.attributes().forEach((key, value) -> json.append(',').append(attribute(key, value)));
            json.append("],\"status\":{\"code\":").append(switch (span.status()) {
                case OK -> 1;
                case ERROR -> 2;
                case UNSET -> 0;
            }).append("}}");
            return json.toString();
        }

        /** OTLP SpanKind: 1 internal, 2 server, 3 client. */
        private static int kind(Span span) {
            if (span.kind() == SpanKind.REQUEST) {
                return "browser".equals(span.source()) ? 3 : 2;
            }
            return span.kind() == SpanKind.HTTP_CLIENT || span.kind() == SpanKind.DATABASE ? 3 : 1;
        }

        private static String attribute(String key, String value) {
            return "{\"key\":" + string(key) + ",\"value\":{\"stringValue\":" + string(value) + "}}";
        }

        static String string(String value) {
            StringBuilder out = new StringBuilder(value.length() + 2).append('"');
            for (char c : value.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.append('"').toString();
        }
    }
}
