// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.export;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.capture.SpanRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracePersistenceTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private final SpanRedactor redactor = new SpanRedactor(SensitiveData.forExport(List.of()));

    @Test
    void tracesSurviveARestartWithSecretsRedactedOnDisk(@TempDir Path dir) throws Exception {
        TraceStore before = new TraceStore();
        before.add(new Span(TRACE, "00f067aa0ba902b7", null, SpanKind.REQUEST, "POST /api/orders", "app", 1, 2,
                SpanStatus.OK, Map.of("http.request.header.authorization", "Bearer live-secret", "http.route", "/api/orders")));
        try (TracePersistence persistence = new TracePersistence(before, dir, redactor, "app", Duration.ofHours(1))) {
            persistence.saveIfChanged();
        }

        String onDisk = Files.readString(dir.resolve(TracePersistence.FILE_NAME));
        assertThat(onDisk).contains("\"format\":\"causeline-store\"", "[REDACTED]").doesNotContain("live-secret");

        TraceStore after = new TraceStore();
        try (TracePersistence persistence = new TracePersistence(after, dir, redactor, "app", Duration.ofHours(1))) {
            assertThat(persistence.load()).isEqualTo(1);
        }
        assertThat(after.get(TRACE)).hasValueSatisfying(spans -> assertThat(spans.getFirst().name()).isEqualTo("POST /api/orders"));
    }

    @Test
    void anUnreadableFileIsLeftAloneInsteadOfFailingStartup(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(TracePersistence.FILE_NAME), "{not json");
        TraceStore store = new TraceStore();

        try (TracePersistence persistence = new TracePersistence(store, dir, redactor, "app", Duration.ofHours(1))) {
            assertThat(persistence.load()).isZero();
        }
        assertThat(store.size()).isZero();
    }
}
