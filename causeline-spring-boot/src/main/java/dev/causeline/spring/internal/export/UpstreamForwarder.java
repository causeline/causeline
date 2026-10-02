// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.export;

import dev.causeline.core.Span;
import dev.causeline.spring.autoconfigure.CauselineProperties.Upstream;
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
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends this service's spans to another application's Causeline ({@code causeline.export.upstream}),
 * so a request that crosses services shows as one trace in the calling service's UI.
 *
 * <p>Like the OTLP export, it sends what Causeline stored, through the export {@link SpanRedactor},
 * from a background thread, dropping and counting when the queue is full or the upstream is down.
 * Spans that themselves arrived from a peer are never sent on, so two services pointing at each
 * other can't loop.
 */
public final class UpstreamForwarder implements SpanForwarder, AutoCloseable {

    /** Marks spans that came from another Causeline; they are stored but never forwarded again. */
    public static final String FORWARDED_ATTRIBUTE = "causeline.forwarded";
    public static final String PEER_PATH = "/causeline/api/peer-spans";
    static final String TOKEN_HEADER = "X-Causeline-Token";

    private static final Logger log = LoggerFactory.getLogger(UpstreamForwarder.class);
    private static final int QUEUE_CAPACITY = 4_096;
    private static final int MAX_BATCH = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final CauselineStats stats;
    private final SpanRedactor redactor;
    private final BlockingQueue<Span> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final HttpClient http;
    private final ScheduledExecutorService worker;
    private volatile boolean warned;

    public UpstreamForwarder(Upstream config, CauselineStats stats, Duration interval, SpanRedactor redactor) {
        String base = config.url().endsWith("/") ? config.url().substring(0, config.url().length() - 1) : config.url();
        this.endpoint = URI.create(base + PEER_PATH);
        this.token = config.token();
        this.timeout = config.timeout();
        this.stats = stats;
        this.redactor = redactor;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "causeline-upstream-export");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::flush, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public String host() {
        return endpoint.getHost() + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
    }

    @Override
    public void forward(Collection<Span> spans) {
        for (Span span : spans) {
            if (span.attributes().containsKey(FORWARDED_ATTRIBUTE)) {
                continue;
            }
            if (!queue.offer(span)) {
                stats.upstreamDropped.incrementAndGet();
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
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(batch.stream().map(redactor::redact).toList())));
        if (token != null && !token.isBlank()) {
            request.header(TOKEN_HEADER, token);
        }
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                stats.upstreamSent.addAndGet(batch.size());
                return;
            }
            failed(batch, response.statusCode() == 401
                    ? "HTTP 401: set causeline.export.upstream.token to the upstream app's causeline.access-token"
                    : "HTTP " + response.statusCode());
        } catch (IOException e) {
            failed(batch, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(batch, "interrupted");
        }
    }

    private void failed(List<Span> batch, String reason) {
        stats.upstreamFailedRequests.incrementAndGet();
        stats.upstreamDropped.addAndGet(batch.size());
        if (!warned) {
            warned = true; // once is enough: the status API keeps counting
            log.warn("Causeline could not send {} spans to the upstream Causeline at {}: {}. "
                    + "Further failures are counted, not logged.", batch.size(), host(), reason);
        }
    }
}
