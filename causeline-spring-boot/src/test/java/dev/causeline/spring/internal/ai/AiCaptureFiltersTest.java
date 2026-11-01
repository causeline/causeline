// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.spring.autoconfigure.CauselineProperties.Ai;
import dev.causeline.spring.autoconfigure.CauselineProperties.AiHints;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.tracing.SpanMapper;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

class AiCaptureFiltersTest {

    private static final Ai ALL = new Ai(true, true, true);
    private static final AiHints NO_HINTS = new AiHints(0, null);
    private static final SensitiveData NO_KEYS = SensitiveData.userBlocked(List.of());

    private static Map<String, String> keys(Observation.Context context) {
        return context.getHighCardinalityKeyValues().stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    private static ChatModelObservationContext chat(String user) {
        ChatModelObservationContext context = ChatModelObservationContext.builder()
                .prompt(new Prompt(List.of(new SystemMessage("Be \"brief\""), new UserMessage(user))))
                .provider("fake")
                .build();
        context.setResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("Fourteen days."),
                ChatGenerationMetadata.builder().finishReason("STOP").build()))));
        return context;
    }

    private static VectorStoreObservationContext search(List<Document> documents) {
        VectorStoreObservationContext context = VectorStoreObservationContext.builder("simple",
                VectorStoreObservationContext.Operation.QUERY)
                .queryRequest(SearchRequest.builder().query("refund time").build())
                .build();
        context.setQueryResponse(documents);
        return context;
    }

    @Test
    void writesTheHintLimitsOntoTheSpansTheyJudge() {
        AiHints hints = new AiHints(0.7, 8000);

        assertThat(keys(new VectorSearchCaptureFilter(ALL, hints, NO_KEYS).map(search(List.of()))))
                .containsEntry("causeline.capture.ai.low_score", "0.7");
        assertThat(keys(new ModelCaptureFilter(ALL, hints, NO_KEYS).map(chat("Hello"))))
                .containsEntry("causeline.capture.ai.context_limit", "8000");

        // Off means no attribute, so the rule has nothing to apply.
        assertThat(keys(new VectorSearchCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(search(List.of()))))
                .doesNotContainKey("causeline.capture.ai.low_score");
        assertThat(keys(new ModelCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(chat("Hello"))))
                .doesNotContainKey("causeline.capture.ai.context_limit");
    }

    @Test
    void chatCallsKeepMessagesInOpenTelemetrysFormatWithTheirFullLength() {
        Map<String, String> keys = keys(new ModelCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(chat("How long?\nReally?")));

        assertThat(keys).containsEntry(SpanMapper.KIND_ATTRIBUTE, "AI_MODEL")
                .containsEntry(AiAttributes.CAPTURED_INPUT,
                        "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"How long?\\nReally?\"}]}]")
                .containsEntry(AiAttributes.CAPTURED_SYSTEM, "[{\"type\":\"text\",\"content\":\"Be \\\"brief\\\"\"}]")
                .containsEntry(AiAttributes.CAPTURED_OUTPUT,
                        "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"Fourteen days.\"}],\"finish_reason\":\"STOP\"}]")
                .containsEntry(AiAttributes.CAPTURED_INPUT_CHARS, "27")
                .containsEntry(AiAttributes.CAPTURED_OUTPUT_CHARS, "14");
    }

    @Test
    void longPromptsAreCutButReportTheirFullLength() {
        String huge = "x".repeat(200_000);

        Map<String, String> keys = keys(new ModelCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(chat(huge)));

        assertThat(keys.get(AiAttributes.CAPTURED_INPUT)).hasSizeLessThan(AiText.MAX_TEXT_CHARS + 200);
        assertThat(keys).containsEntry(AiAttributes.CAPTURED_INPUT_CHARS, "200010");
    }

    @Test
    void aCallWithNoResponseKeepsItsPrompt() {
        ChatModelObservationContext failed = ChatModelObservationContext.builder()
                .prompt(new Prompt("Fail please")).provider("fake").build();

        assertThat(keys(new ModelCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(failed)))
                .containsKey(AiAttributes.CAPTURED_INPUT).doesNotContainKey(AiAttributes.CAPTURED_OUTPUT);
    }

    @Test
    void eachSwitchBlocksItsText() {
        assertThat(keys(new ModelCaptureFilter(new Ai(false, true, true), NO_HINTS, NO_KEYS).map(chat("SECRET"))))
                .containsKey(AiAttributes.CAPTURED_OUTPUT).containsEntry(SpanMapper.KIND_ATTRIBUTE, "AI_MODEL")
                .doesNotContainKeys(AiAttributes.CAPTURED_INPUT, AiAttributes.CAPTURED_SYSTEM);
        assertThat(keys(new ModelCaptureFilter(new Ai(true, false, true), NO_HINTS, NO_KEYS).map(chat("SECRET"))))
                .containsKey(AiAttributes.CAPTURED_INPUT).doesNotContainKey(AiAttributes.CAPTURED_OUTPUT);

        Map<String, String> search = keys(new VectorSearchCaptureFilter(new Ai(true, true, false), NO_HINTS, NO_KEYS)
                .map(search(List.of(Document.builder().id("doc-1").text("SECRET").score(0.9).build()))));
        assertThat(search).containsEntry(AiAttributes.CAPTURED_DOCUMENTS, "[{\"id\":\"doc-1\",\"rank\":1,\"chars\":6,\"score\":0.9}]")
                .containsEntry(AiAttributes.CAPTURED_COUNT, "1")
                .doesNotContainKeys(AiAttributes.CAPTURED_CONTENT, AiAttributes.CAPTURED_QUERY);
    }

    @Test
    void embeddingInputIsAQuestionOrADocumentSoEitherSwitchBlocksIt() {
        EmbeddingModelObservationContext context = EmbeddingModelObservationContext.builder()
                .embeddingRequest(new EmbeddingRequest(List.of("SECRET"), null)).provider("fake").build();

        assertThat(keys(new ModelCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(context)))
                .containsEntry(AiAttributes.CAPTURED_EMBEDDING, "[\"SECRET\"]");

        EmbeddingModelObservationContext blocked = EmbeddingModelObservationContext.builder()
                .embeddingRequest(new EmbeddingRequest(List.of("SECRET"), null)).provider("fake").build();
        assertThat(keys(new ModelCaptureFilter(new Ai(true, true, false), NO_HINTS, NO_KEYS).map(blocked)))
                .doesNotContainKey(AiAttributes.CAPTURED_EMBEDDING);
    }

    @Test
    void searchesKeepEachDocumentInRankOrderAndCapItsText() {
        Document big = Document.builder().id("big").text("y".repeat(10_000)).score(0.8)
                .metadata(Map.of("source", "policy.md")).build();
        Document noScore = Document.builder().id("none").text("short").build();

        Map<String, String> keys = keys(new VectorSearchCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(search(List.of(big, noScore))));

        assertThat(keys).containsEntry(SpanMapper.KIND_ATTRIBUTE, "AI_RETRIEVAL")
                .containsEntry(AiAttributes.CAPTURED_QUERY, "refund time")
                .containsEntry(AiAttributes.CAPTURED_DOCUMENTS,
                        "[{\"id\":\"big\",\"rank\":1,\"chars\":10000,\"score\":0.8},{\"id\":\"none\",\"rank\":2,\"chars\":5}]");
        assertThat(keys.get(AiAttributes.CAPTURED_CONTENT)).hasSizeLessThan(AiText.MAX_DOCUMENT_CHARS + 100)
                .contains("\"source\":\"policy.md\"", "\"text\":\"short\"");
    }

    @Test
    void searchesKeepAtMostFiftyDocumentsButCountThemAll() {
        List<Document> many = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> Document.builder().id("d" + i).text("t").build()).toList();

        Map<String, String> keys = keys(new VectorSearchCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(search(many)));

        assertThat(keys).containsEntry(AiAttributes.CAPTURED_COUNT, "60");
        assertThat(keys.get(AiAttributes.CAPTURED_DOCUMENTS)).contains("\"d49\"").doesNotContain("\"d50\"");
    }

    @Test
    void addingDocumentsAndOtherObservationsAreLeftAlone() {
        VectorStoreObservationContext add = VectorStoreObservationContext.builder("simple",
                VectorStoreObservationContext.Operation.ADD).build();
        Observation.Context other = new Observation.Context();

        assertThat(keys(new VectorSearchCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(add))).isEmpty();
        assertThat(keys(new ModelCaptureFilter(ALL, NO_HINTS, NO_KEYS).map(other))).isEmpty();
    }

    @Test
    void redactKeysHideValuesEvenLocally() {
        SensitiveData blocked = SensitiveData.userBlocked(List.of("password"));

        Map<String, String> keys = keys(new ModelCaptureFilter(ALL, NO_HINTS, blocked).map(chat("My password=SECRET-VALUE ok?")));

        assertThat(keys.get(AiAttributes.CAPTURED_INPUT)).doesNotContain("SECRET-VALUE").contains("[REDACTED]");
    }
}
