// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.export;

import dev.causeline.core.Span;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.internal.capture.SpanRedactor;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps traces across restarts ({@code causeline.store.persist=true}): the store is saved to one
 * JSON file in the background when it has changed, and once more on shutdown, then loaded at the
 * next start.
 *
 * <p>Disk counts as leaving the running application, so spans are written through the export
 * {@link SpanRedactor}: credentials and sensitive values are not stored on disk unless
 * {@code causeline.export.redact-secrets=false}. Writes go to a temporary file that replaces the
 * old one, so a crash never leaves a half-written store. Nothing here runs on a request thread.
 */
public final class TracePersistence implements AutoCloseable {

    public static final String FORMAT = "causeline-store";
    static final String FILE_NAME = "traces.json";

    private static final Logger log = LoggerFactory.getLogger(TracePersistence.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TraceStore store;
    private final Path file;
    private final SpanRedactor redactor;
    private final String appName;
    private final ScheduledExecutorService worker;
    private long savedFingerprint = Long.MIN_VALUE;
    private volatile boolean warned;

    /** The file's content. */
    public record Snapshot(String format, int schemaVersion, String savedAt, String app, List<Span> spans) {
    }

    public TracePersistence(TraceStore store, Path directory, SpanRedactor redactor, String appName, Duration interval) {
        this.store = store;
        this.file = directory.resolve(FILE_NAME);
        this.redactor = redactor;
        this.appName = appName;
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "causeline-store-save");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::saveIfChanged, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Path file() {
        return file;
    }

    /** Loads the traces saved by the previous run, if any. A file it can't read is left alone. */
    public int load() {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            Snapshot snapshot = JSON.readValue(file.toFile(), Snapshot.class);
            if (!FORMAT.equals(snapshot.format()) || snapshot.schemaVersion() != Span.SCHEMA_VERSION
                    || snapshot.spans() == null) {
                log.info("Causeline did not load {}: written by a different version.", file);
                return 0;
            }
            store.addAll(snapshot.spans());
            savedFingerprint = fingerprint();
            return snapshot.spans().size();
        } catch (RuntimeException e) {
            log.warn("Causeline could not read saved traces from {}: {}", file, e.getMessage());
            return 0;
        }
    }

    /** Saves when something changed since the last save. Runs on the save thread and on shutdown. */
    public synchronized void saveIfChanged() {
        long current = fingerprint();
        if (current == savedFingerprint) {
            return;
        }
        List<Span> spans = new ArrayList<>();
        for (List<Span> trace : store.snapshot()) {
            for (Span span : trace) {
                spans.add(redactor.redact(span));
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "traces-", ".tmp");
            JSON.writeValue(temp.toFile(),
                    new Snapshot(FORMAT, Span.SCHEMA_VERSION, Instant.now().toString(), appName, spans));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            savedFingerprint = current;
        } catch (IOException | RuntimeException e) {
            if (!warned) {
                warned = true;
                log.warn("Causeline could not save traces to {}: {}. Further failures are not logged.", file,
                        e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        worker.shutdown();
        saveIfChanged();
    }

    /** Cheap change detection: the store only grows, evicts or is replaced as a whole. */
    private long fingerprint() {
        return store.size() * 1_000_003L + store.estimatedBytes() * 31 + store.evictedTraces();
    }
}
