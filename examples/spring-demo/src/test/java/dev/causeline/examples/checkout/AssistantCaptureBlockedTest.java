// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** SC-19: with every capture.ai switch off, no text reaches a span, not even what Spring AI records itself. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"demo.model.port=18092",
        "causeline.capture.ai.prompts=false", "causeline.capture.ai.completions=false",
        "causeline.capture.ai.documents=false"})
@ActiveProfiles("dev")
class AssistantCaptureBlockedTest {

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Test
    void blockedTextIsGoneButTheShapeRemains() throws Exception {
        String traceId = "a3f92f3577b34da6a3ce929d0e0e4736";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/ask"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"How long do refunds take?\"}"))
                .build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Span> spans = List.of();
        while (System.nanoTime() < deadline && spans.stream().noneMatch(s -> s.name().startsWith("chat"))) {
            spans = store.get(traceId).orElse(List.of());
            Thread.sleep(100);
        }

        Span search = spans.stream().filter(s -> s.kind() == SpanKind.AI_RETRIEVAL).findFirst().orElseThrow();
        assertThat(search.attributes()).containsKey("causeline.ai.documents").containsEntry("causeline.ai.documents.count", "3")
                .doesNotContainKeys("db.vector.query.content", "causeline.ai.documents.content");
        assertThat(search.attributes().toString()).doesNotContain("How long do refunds take");

        Span chat = spans.stream().filter(s -> s.name().startsWith("chat")).findFirst().orElseThrow();
        assertThat(chat.attributes()).containsEntry("gen_ai.usage.input_tokens", "120")
                .doesNotContainKeys("gen_ai.input.messages", "gen_ai.output.messages", "causeline.ai.input.chars");

        Span embedding = spans.stream().filter(s -> s.name().startsWith("embedding")).findFirst().orElseThrow();
        assertThat(embedding.attributes()).doesNotContainKey("causeline.ai.embedding.input");
    }
}
