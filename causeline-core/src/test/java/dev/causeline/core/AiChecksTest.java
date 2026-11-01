// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.Insight.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SC-20: each hint fires on a seeded trace, stays silent on a clean one, and carries no captured text. */
class AiChecksTest {

    private static final String WITHHELD = "[withheld on export]";
    private static final String CHUNK = "SECRET-CHUNK refunds take fourteen days";
    private static final String OTHER = "SECRET-OTHER shipping takes three days";

    private int nextId = 1;

    @Test
    void aCleanRetrievalAndChatRaiseNothing() {
        assertThat(analyze(search(0, Map.of(
                "causeline.ai.low_score", "0.5",
                "causeline.ai.documents.count", "2",
                "causeline.ai.documents", documents("a", 0.9, "b", 0.6),
                "causeline.ai.documents.content", content(CHUNK, OTHER))),
                chat(10, prompt("Context: " + CHUNK + "\n" + OTHER), Map.of(
                        "gen_ai.response.finish_reasons", "[\"STOP\"]",
                        "causeline.ai.context_limit", "1000",
                        "gen_ai.usage.input_tokens", "900"))))
                .isEmpty();
    }

    @Test
    void emptyRetrieval() {
        assertThat(rules(search(0, Map.of("causeline.ai.documents.count", "0", "causeline.ai.documents", "[]"))))
                .containsExactly(Rule.EMPTY_RETRIEVAL);
    }

    @Test
    void lowRelevanceComparesTheBestScoreWithTheLimitOnTheSpan() {
        assertThat(rules(search(0, Map.of("causeline.ai.low_score", "0.5",
                "causeline.ai.documents", documents("a", 0.21, "b", 0.4))))).containsExactly(Rule.LOW_RELEVANCE);
        assertThat(rules(search(0, Map.of("causeline.ai.low_score", "0.5",
                "causeline.ai.documents", documents("a", 0.5, "b", 0.1))))).isEmpty();
        // Off when the limit isn't set, and no opinion without scores.
        assertThat(rules(search(0, Map.of("causeline.ai.documents", documents("a", 0.21))))).isEmpty();
        assertThat(rules(search(0, Map.of("causeline.ai.low_score", "0.5",
                "causeline.ai.documents", "[{\"id\":\"a\",\"rank\":1,\"chars\":3}]")))).isEmpty();
    }

    @Test
    void duplicateChunksByIdByTextAndInThePrompt() {
        assertThat(rules(search(0, Map.of("causeline.ai.documents", documents("a", 0.9, "a", 0.8)))))
                .containsExactly(Rule.DUPLICATE_CHUNKS);
        assertThat(rules(search(0, Map.of("causeline.ai.documents", documents("a", 0.9, "b", 0.8),
                "causeline.ai.documents.content", content(CHUNK, CHUNK))))).containsExactly(Rule.DUPLICATE_CHUNKS);

        TraceView.Row search = search(0, Map.of("causeline.ai.documents", documents("a", 0.9),
                "causeline.ai.documents.content", content(CHUNK)));
        List<Insight> insights = analyze(search, chat(10, prompt(CHUNK + " again " + CHUNK), Map.of()));

        assertThat(insights).extracting(Insight::rule).containsExactly(Rule.DUPLICATE_CHUNKS);
        assertThat(insights.getFirst().spanId()).isNotEqualTo(search.spanId());
    }

    @Test
    void overlapBetweenExpandedSearchesIsNotADuplicate() {
        Map<String, String> found = Map.of("causeline.ai.documents", documents("a", 0.9),
                "causeline.ai.documents.content", content(CHUNK));

        assertThat(analyze(search(0, found), search(1, found), chat(10, prompt(CHUNK), Map.of()))).isEmpty();
    }

    @Test
    void chunkNotInPromptMatchesAcrossLineEndings() {
        Map<String, String> found = Map.of("causeline.ai.documents", documents("a", 0.9, "b", 0.8),
                "causeline.ai.documents.content", content("First\nline of it", OTHER));

        List<Insight> insights = analyze(search(0, found), chat(10, prompt("Say\r\nFirst\r\nline of it"), Map.of()));

        assertThat(insights).extracting(Insight::rule).containsExactly(Rule.CHUNK_NOT_IN_PROMPT);
        assertThat(insights.getFirst().label()).contains("1 of 2 chunks");
    }

    @Test
    void chunkNotInPromptClaimsNothingWithoutTheWholePrompt() {
        Map<String, String> found = Map.of("causeline.ai.documents", documents("a", 0.9),
                "causeline.ai.documents.content", content(OTHER));

        assertThat(analyze(search(0, found), chat(10, prompt("Short"), Map.of("causeline.ai.input.chars", "200000"))))
                .isEmpty();
        assertThat(analyze(search(0, found), chat(10, null, Map.of()))).isEmpty();
        // A search after the chat call didn't feed it, and a prompt on Windows isn't "cut".
        assertThat(analyze(chat(0, prompt("Short"), Map.of()), search(100, found))).isEmpty();
        String crlf = "A\r\nB";
        assertThat(analyze(search(0, Map.of("causeline.ai.documents", documents("a", 0.9),
                "causeline.ai.documents.content", content("A\nB"))),
                chat(10, prompt(crlf), Map.of("causeline.ai.input.chars", String.valueOf(crlf.length()))))).isEmpty();
    }

    @Test
    void aSearchIsJudgedAgainstTheChatCallRightAfterIt() {
        Map<String, String> first = Map.of("causeline.ai.documents", documents("a", 0.9),
                "causeline.ai.documents.content", content(CHUNK));
        Map<String, String> second = Map.of("causeline.ai.documents", documents("b", 0.9),
                "causeline.ai.documents.content", content(OTHER));

        assertThat(analyze(search(0, first), chat(10, prompt(CHUNK), Map.of()),
                search(100, second), chat(110, prompt(OTHER), Map.of()))).isEmpty();
    }

    @Test
    void answerCutOffIgnoresCase() {
        for (String reason : List.of("LENGTH", "length", "max_tokens", "MAX_TOKENS")) {
            assertThat(rules(chat(0, null, Map.of("gen_ai.response.finish_reasons", "[\"" + reason + "\"]"))))
                    .containsExactly(Rule.ANSWER_CUT_OFF);
        }
        assertThat(rules(chat(0, null, Map.of("gen_ai.response.finish_reasons", "[\"STOP\"]")))).isEmpty();
    }

    @Test
    void contextNearLimitIsOffUnlessALimitIsSet() {
        assertThat(rules(chat(0, null, Map.of("causeline.ai.context_limit", "1000", "gen_ai.usage.input_tokens", "901"))))
                .containsExactly(Rule.CONTEXT_NEAR_LIMIT);
        assertThat(rules(chat(0, null, Map.of("gen_ai.usage.input_tokens", "901000")))).isEmpty();
    }

    @Test
    void repeatedEmbedding() {
        Map<String, String> input = Map.of("gen_ai.operation.name", "embedding",
                "causeline.ai.embedding.input", "[\"same\"]");
        Map<String, String> other = Map.of("gen_ai.operation.name", "embedding",
                "causeline.ai.embedding.input", "[\"different\"]");

        assertThat(rules(span(SpanKind.AI_MODEL, 0, input), span(SpanKind.AI_MODEL, 5, input)))
                .containsExactly(Rule.REPEATED_EMBEDDING);
        assertThat(rules(span(SpanKind.AI_MODEL, 0, input), span(SpanKind.AI_MODEL, 5, other))).isEmpty();
    }

    @Test
    void anImportedTraceKeepsTheMetadataRulesAndNeverReadsWithheldText() {
        TraceView.Row search = search(0, Map.of("causeline.ai.low_score", "0.5",
                "causeline.ai.documents", documents("a", 0.2),
                "causeline.ai.documents.content", WITHHELD));
        TraceView.Row chat = span(SpanKind.AI_MODEL, 10, Map.of("gen_ai.operation.name", "chat",
                "gen_ai.input.messages", WITHHELD));
        TraceView.Row embedded = span(SpanKind.AI_MODEL, 20, Map.of("causeline.ai.embedding.input", WITHHELD));
        TraceView.Row again = span(SpanKind.AI_MODEL, 30, Map.of("causeline.ai.embedding.input", WITHHELD));

        assertThat(rules(search, chat, embedded, again)).containsExactly(Rule.LOW_RELEVANCE);
    }

    @Test
    void noLabelCarriesCapturedText() {
        List<Insight> insights = analyze(
                search(0, Map.of("causeline.ai.low_score", "0.5",
                        "db.vector.query.content", "SECRET-QUERY",
                        "causeline.ai.documents", documents("a", 0.2, "a", 0.1),
                        "causeline.ai.documents.content", content(CHUNK, OTHER))),
                chat(10, prompt("SECRET-PROMPT " + OTHER + " " + OTHER), Map.of(
                        "gen_ai.response.finish_reasons", "[\"LENGTH\"]",
                        "causeline.ai.context_limit", "100",
                        "gen_ai.usage.input_tokens", "99")),
                span(SpanKind.AI_MODEL, 20, Map.of("causeline.ai.embedding.input", "[\"SECRET-INPUT\"]")),
                span(SpanKind.AI_MODEL, 30, Map.of("causeline.ai.embedding.input", "[\"SECRET-INPUT\"]")));

        assertThat(insights).extracting(Insight::rule).contains(Rule.LOW_RELEVANCE, Rule.DUPLICATE_CHUNKS,
                Rule.CHUNK_NOT_IN_PROMPT, Rule.ANSWER_CUT_OFF, Rule.CONTEXT_NEAR_LIMIT, Rule.REPEATED_EMBEDDING);
        assertThat(insights).extracting(Insight::label).noneMatch(label -> label.contains("SECRET"));
    }

    @Test
    void aiRulesAreNotSecurityRules() {
        assertThat(Rule.CHUNK_NOT_IN_PROMPT.isSecurity()).isFalse();
        assertThat(Rule.REPEATED_EMBEDDING.isSecurity()).isFalse();
    }

    private List<Rule> rules(TraceView.Row... rows) {
        return analyze(rows).stream().map(Insight::rule).toList();
    }

    private List<Insight> analyze(TraceView.Row... rows) {
        List<Insight> insights = new ArrayList<>();
        AiChecks.analyze(List.of(rows), insights);
        return insights;
    }

    private TraceView.Row search(long offsetMs, Map<String, String> attributes) {
        return span(SpanKind.AI_RETRIEVAL, offsetMs, attributes);
    }

    private TraceView.Row chat(long offsetMs, String prompt, Map<String, String> attributes) {
        Map<String, String> all = new java.util.HashMap<>(attributes);
        all.put("gen_ai.operation.name", "chat");
        if (prompt != null) {
            all.put("gen_ai.input.messages", prompt);
        }
        return span(SpanKind.AI_MODEL, offsetMs, all);
    }

    private TraceView.Row span(SpanKind kind, long offsetMs, Map<String, String> attributes) {
        return new TraceView.Row(String.valueOf(nextId++), null, kind, kind.name().toLowerCase(), "app", SpanStatus.OK, 0,
                offsetMs * 1_000_000, 5_000_000, 0, false, attributes);
    }

    private static String documents(Object... idsAndScores) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < idsAndScores.length; i += 2) {
            entries.add("{\"id\":\"" + idsAndScores[i] + "\",\"rank\":" + (i / 2 + 1) + ",\"chars\":10,\"score\":"
                    + idsAndScores[i + 1] + "}");
        }
        return "[" + String.join(",", entries) + "]";
    }

    private static String content(String... texts) {
        List<String> entries = new ArrayList<>();
        for (String text : texts) {
            entries.add("{\"text\":\"" + text.replace("\n", "\\n") + "\"}");
        }
        return "[" + String.join(",", entries) + "]";
    }

    private static String prompt(String text) {
        return "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\""
                + text.replace("\r", "\\r").replace("\n", "\\n") + "\"}]}]";
    }
}
