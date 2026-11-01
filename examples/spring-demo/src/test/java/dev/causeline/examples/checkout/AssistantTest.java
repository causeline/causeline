// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Insight;
import dev.causeline.core.Insight.Rule;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceAssembler;
import dev.causeline.core.TraceStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** SC-18 and SC-19: a RAG request through the real Spring AI client, against the fake model. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"causeline.access-token=" + AssistantTest.TOKEN, "demo.model.port=18091",
                "causeline.mcp.replay.allow[0]=POST /api/ask"})
@ActiveProfiles("dev")
class AssistantTest {

    static final String TOKEN = "assistant-test-token";
    private static final String BROWSER_SPAN_ID = "00f067aa0ba902b7";
    private static final Set<Rule> AI_RULES = EnumSet.range(Rule.EMPTY_RETRIEVAL, Rule.REPEATED_EMBEDDING);

    @Autowired
    private Environment environment;

    @Autowired
    private TraceStore store;

    @Autowired
    private org.springframework.ai.vectorstore.VectorStore documents;

    @Autowired
    private FakeModelServer model;

    @Test
    void oneQuestionIsOneLinkedTraceFromTheRequestToTheAnswer() throws Exception {
        String traceId = "a1f92f3577b34da6a3ce929d0e0e4736";

        HttpResponse<String> response = ask("How long do refunds take?", traceId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("14 days");
        List<Span> spans = await(traceId);
        Map<String, Span> byId = spans.stream().collect(Collectors.toMap(Span::spanId, s -> s));

        Span request = find(spans, s -> s.kind() == SpanKind.REQUEST && s.name().equals("POST /api/ask"));
        assertThat(request.parentSpanId()).isEqualTo(BROWSER_SPAN_ID);

        Span search = find(spans, s -> s.kind() == SpanKind.AI_RETRIEVAL);
        assertThat(search.attributes())
                .containsEntry("db.operation.name", "query")
                .containsEntry("db.vector.query.top_k", "3")
                .containsEntry("db.vector.query.content", "How long do refunds take?")
                .containsEntry("causeline.ai.documents.count", "3");
        assertThat(search.attributes().get("causeline.ai.documents")).contains("\"id\":\"refunds\"", "\"rank\":1", "\"score\":");
        assertThat(search.attributes().get("causeline.ai.documents.content")).contains("policy.md", "within 14 days");

        Span embedding = find(spans, s -> s.kind() == SpanKind.AI_MODEL && s.name().startsWith("embedding"));
        assertThat(embedding.parentSpanId()).isEqualTo(search.spanId());
        assertThat(embedding.attributes())
                .containsEntry("gen_ai.request.model", "fake-embed")
                .containsEntry("causeline.ai.embedding.input", "[\"How long do refunds take?\"]");

        Span chat = find(spans, s -> s.kind() == SpanKind.AI_MODEL && s.name().startsWith("chat"));
        assertThat(chat.attributes())
                .containsEntry("gen_ai.response.model", "fake-chat")
                .containsEntry("gen_ai.usage.input_tokens", "120")
                .containsEntry("gen_ai.usage.output_tokens", "12")
                .containsEntry("gen_ai.response.finish_reasons", "[\"STOP\"]");
        assertThat(chat.attributes().get("gen_ai.input.messages")).contains("How long do refunds take?", "within 14 days");
        assertThat(chat.attributes().get("gen_ai.output.messages")).contains("14 days", "\"finish_reason\":\"STOP\"");

        // Both hang under the same request, and advisors read as their names.
        assertThat(ancestors(chat, byId)).contains(request.spanId());
        assertThat(ancestors(search, byId)).contains(request.spanId());
        assertThat(spans).extracting(Span::name).contains("ChatClient", "QuestionAnswerAdvisor");

        // The model's HTTP call sits under the chat span, with a readable name and no API key.
        Span http = find(spans, s -> s.kind() == SpanKind.HTTP_CLIENT && s.parentSpanId().equals(chat.spanId()));
        assertThat(http.name()).isEqualTo("POST localhost");
        assertThat(spans).allSatisfy(s -> assertThat(s.attributes().toString()).doesNotContain("demo-model-key"));

        // A vector search is not a database query.
        assertThat(spans).noneMatch(s -> s.kind() == SpanKind.DATABASE);
    }

    @Test
    void aiTextIsShownLocallyButLeavesNoExportOrAgentResult() throws Exception {
        String traceId = "a2f92f3577b34da6a3ce929d0e0e4736";
        model.start();
        documents.add(List.of(new org.springframework.ai.document.Document("seeded",
                "How long do refunds take? SECRET-IN-DOCUMENT", Map.of())));

        ask("How long do refunds take?", traceId);

        List<Span> spans = await(traceId);
        assertThat(find(spans, s -> s.kind() == SpanKind.AI_RETRIEVAL).attributes().get("causeline.ai.documents.content"))
                .contains("SECRET-IN-DOCUMENT");
        assertThat(find(spans, s -> s.name().startsWith("chat")).attributes().get("gen_ai.input.messages"))
                .contains("SECRET-IN-DOCUMENT");

        String exported = get("/causeline/api/traces/" + traceId + "/export").body();
        // The answer stays visible in the application's own response body, which follows the body rules.
        assertThat(exported).doesNotContain("SECRET-IN-DOCUMENT", "A refund needs the original receipt")
                .contains("[withheld on export]", "seeded", "gen_ai.usage.input_tokens");

        String agent = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_trace\","
                + "\"arguments\":{\"traceId\":\"" + traceId + "\"}}}").body();
        assertThat(agent).doesNotContain("SECRET-IN-DOCUMENT").contains("[withheld on export]");
    }

    @Test
    void aGoodMatchRaisesNoHintsAndEachDemoSwitchRaisesItsOwn() throws Exception {
        assertThat(aiHints("When are refunds issued?", null, "a3f92f3577b34da6a3ce929d0e0e4736")).isEmpty();
        assertThat(aiHints("Can I pay with crypto?", null, "a4f92f3577b34da6a3ce929d0e0e4736"))
                .containsExactly(Rule.LOW_RELEVANCE);
        assertThat(aiHints("When are refunds issued?", "cut-off", "a5f92f3577b34da6a3ce929d0e0e4736"))
                .containsExactly(Rule.ANSWER_CUT_OFF);
        assertThat(aiHints("When are refunds issued?", "reranked", "a6f92f3577b34da6a3ce929d0e0e4736"))
                .containsExactly(Rule.CHUNK_NOT_IN_PROMPT);
    }

    @Test
    void nothingSpendsModelTokensByAccident() throws Exception {
        String traceId = "a7f92f3577b34da6a3ce929d0e0e4736";
        ask("When are refunds issued?", traceId);
        String span = find(await(traceId), s -> s.kind() == SpanKind.REQUEST && s.name().equals("POST /api/ask")).spanId();

        HttpResponse<String> load = post("/causeline/api/load", "{\"traceId\":\"" + traceId + "\",\"spanId\":\"" + span
                + "\",\"hits\":3,\"concurrency\":1,\"confirm\":true}");
        assertThat(load.statusCode()).isEqualTo(403);
        assertThat(load.body()).contains("causeline.load.allow-ai=true");

        // POST /api/ask is on the allow list, so this refusal is about the model alone.
        String agent = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"replay_request\","
                + "\"arguments\":{\"traceId\":\"" + traceId + "\",\"spanId\":\"" + span + "\",\"confirm\":true}}}").body();
        assertThat(agent).contains("causeline.mcp.replay.allow-ai=true");
    }

    private List<Rule> aiHints(String question, String mode, String traceId) throws Exception {
        assertThat(ask(question, mode, traceId).statusCode()).isEqualTo(200);
        return TraceAssembler.assemble(traceId, await(traceId)).insights().stream()
                .map(Insight::rule)
                .filter(AI_RULES::contains)
                .toList();
    }

    private static List<String> ancestors(Span span, Map<String, Span> byId) {
        List<String> found = new java.util.ArrayList<>();
        for (Span up = byId.get(span.parentSpanId()); up != null; up = byId.get(up.parentSpanId())) {
            found.add(up.spanId());
        }
        return found;
    }

    private static Span find(List<Span> spans, Predicate<Span> test) {
        return spans.stream().filter(test).findFirst().orElseThrow(
                () -> new AssertionError("Not found in " + spans.stream().map(s -> s.kind() + " " + s.name()).toList()));
    }

    private List<Span> await(String traceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Span> spans = List.of();
        while (System.nanoTime() < deadline) {
            spans = store.get(traceId).orElse(List.of());
            if (spans.stream().anyMatch(s -> s.name().equals("POST /api/ask") && s.kind() == SpanKind.REQUEST)
                    && spans.stream().anyMatch(s -> s.name().startsWith("chat"))) {
                return spans;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Trace had: " + spans.stream().map(s -> s.kind() + " " + s.name()).toList());
    }

    private HttpResponse<String> ask(String question, String traceId) throws Exception {
        return ask(question, null, traceId);
    }

    private HttpResponse<String> ask(String question, String mode, String traceId) throws Exception {
        String body = "{\"question\":\"" + question + "\"" + (mode == null ? "" : ",\"mode\":\"" + mode + "\"") + "}";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/ask"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-" + traceId + "-" + BROWSER_SPAN_ID + "-01")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("X-Causeline-Token", TOKEN).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Causeline-Token", TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mcp(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/causeline/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
