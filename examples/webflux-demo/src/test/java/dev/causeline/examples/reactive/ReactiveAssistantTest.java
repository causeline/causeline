// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

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
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** SC-18 on WebFlux: a streamed answer is one linked trace, with the whole answer on its span. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "demo.model.port=18093")
@ActiveProfiles("dev")
class ReactiveAssistantTest {

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Test
    void aStreamedAnswerIsOneLinkedTrace() throws Exception {
        String traceId = "b1f92f3577b34da6a3ce929d0e0e4736";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/ask"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"How long do refunds take?\"}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("within 14 days");

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Span> spans = List.of();
        while (System.nanoTime() < deadline && !complete(spans)) {
            spans = store.get(traceId).orElse(List.of());
            Thread.sleep(100);
        }
        assertThat(complete(spans)).as(spans.stream().map(s -> s.kind() + " " + s.name()).toList().toString()).isTrue();
        Map<String, Span> byId = spans.stream().collect(Collectors.toMap(Span::spanId, s -> s));

        Span server = spans.stream().filter(s -> s.kind() == SpanKind.REQUEST).findFirst().orElseThrow();
        Span search = spans.stream().filter(s -> s.kind() == SpanKind.AI_RETRIEVAL).findFirst().orElseThrow();
        Span chat = spans.stream().filter(s -> s.name().startsWith("chat")).findFirst().orElseThrow();

        assertThat(chat.attributes()).containsEntry("gen_ai.request.stream", "true")
                .containsEntry("gen_ai.usage.output_tokens", "12");
        assertThat(chat.attributes().get("gen_ai.output.messages")).contains("refunds are issued within 14 days");
        assertThat(chat.attributes().get("gen_ai.input.messages")).contains("Refunds are issued within 14 days");
        assertThat(search.attributes().get("causeline.ai.documents")).contains("\"id\":\"refunds\"");

        assertThat(hasAncestor(chat, server, byId)).isTrue();
        assertThat(hasAncestor(search, server, byId)).isTrue();
    }

    private static boolean complete(List<Span> spans) {
        return spans.stream().anyMatch(s -> s.kind() == SpanKind.REQUEST)
                && spans.stream().anyMatch(s -> s.name().startsWith("chat"))
                && spans.stream().anyMatch(s -> s.name().equals("ChatClient"));
    }

    private static boolean hasAncestor(Span span, Span ancestor, Map<String, Span> byId) {
        for (Span up = byId.get(span.parentSpanId()); up != null; up = byId.get(up.parentSpanId())) {
            if (up.spanId().equals(ancestor.spanId())) {
                return true;
            }
        }
        return false;
    }
}
