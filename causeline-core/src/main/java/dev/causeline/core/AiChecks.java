// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import dev.causeline.core.Insight.Rule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Plain rules about the retrieval and model calls in one trace (PRD section 7). No model is involved.
 * Labels name spans and give numbers, never prompt, chunk or query text. Text withheld on export
 * doesn't parse, so a rule that needs it stays silent on an imported trace.
 *
 * <p>The thresholds arrive on the spans ({@code causeline.ai.low_score}, {@code .context_limit}), so
 * a trace is judged by the settings of the application that recorded it.
 */
final class AiChecks {

    private static final String DOCUMENTS = "causeline.ai.documents";
    private static final String DOCUMENT_CONTENT = "causeline.ai.documents.content";
    private static final String DOCUMENT_COUNT = "causeline.ai.documents.count";
    private static final String EMBEDDING_INPUT = "causeline.ai.embedding.input";
    private static final String INPUT_CHARS = "causeline.ai.input.chars";
    private static final String LOW_SCORE = "causeline.ai.low_score";
    private static final String CONTEXT_LIMIT = "causeline.ai.context_limit";

    static final double CONTEXT_WARNING_SHARE = 0.9;
    /** A short chunk can turn up twice in a prompt by chance. */
    static final int MIN_REPEATED_CHUNK_CHARS = 20;

    private AiChecks() {
    }

    static void analyze(List<TraceView.Row> rows, List<Insight> insights) {
        List<TraceView.Row> chats = rows.stream()
                .filter(AiChecks::isChat)
                .sorted(Comparator.comparingLong(TraceView.Row::offsetNanos))
                .toList();
        Map<String, Set<String>> chunksByChat = new LinkedHashMap<>();
        for (TraceView.Row row : rows) {
            if (row.kind() == SpanKind.AI_RETRIEVAL) {
                TraceView.Row chat = chatAfter(row, chats);
                search(row, chat, insights);
                if (chat != null) {
                    chunksByChat.computeIfAbsent(chat.spanId(), k -> new LinkedHashSet<>()).addAll(chunkTexts(row));
                }
            }
        }
        for (TraceView.Row chat : chats) {
            answerCutOff(chat, insights);
            contextNearLimit(chat, insights);
            repeatedChunks(chat, chunksByChat.getOrDefault(chat.spanId(), Set.of()), insights);
        }
        repeatedEmbeddings(rows, insights);
    }

    private static boolean isChat(TraceView.Row row) {
        return row.kind() == SpanKind.AI_MODEL && "chat".equals(row.attributes().get("gen_ai.operation.name"));
    }

    /** The first chat call to start after a search ended, in the same service. The RAG panel pairs them the same way. */
    private static TraceView.Row chatAfter(TraceView.Row search, List<TraceView.Row> chats) {
        long end = search.offsetNanos() + search.durationNanos();
        return chats.stream()
                .filter(chat -> Objects.equals(chat.source(), search.source()) && chat.offsetNanos() >= end)
                .findFirst()
                .orElse(null);
    }

    private static void search(TraceView.Row search, TraceView.Row chat, List<Insight> insights) {
        if ("0".equals(search.attributes().get(DOCUMENT_COUNT))) {
            insights.add(new Insight(Rule.EMPTY_RETRIEVAL, search.spanId(), "Empty retrieval: " + search.name()
                    + " returned no documents, so the model got no context from it", 0));
            return;
        }
        List<Map<String, Object>> documents = AiJson.objects(AiJson.parse(search.attributes().get(DOCUMENTS)));
        lowRelevance(search, documents, insights);
        duplicates(search, documents, insights);
        if (chat != null) {
            chunksMissingFromPrompt(search, chat, insights);
        }
    }

    private static void lowRelevance(TraceView.Row search, List<Map<String, Object>> documents, List<Insight> insights) {
        Double limit = number(search.attributes().get(LOW_SCORE));
        Double best = documents.stream()
                .map(document -> document.get("score"))
                .filter(Double.class::isInstance)
                .map(Double.class::cast)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (limit != null && best != null && best < limit) {
            insights.add(new Insight(Rule.LOW_RELEVANCE, search.spanId(), String.format(Locale.ROOT,
                    "Low relevance: the best score in %s is %.2f, below %s (causeline.ai.low-score)",
                    search.name(), best, limit), 0));
        }
    }

    private static void duplicates(TraceView.Row search, List<Map<String, Object>> documents, List<Insight> insights) {
        Set<Object> ids = new HashSet<>();
        boolean repeated = documents.stream().anyMatch(document -> document.get("id") != null && !ids.add(document.get("id")));
        Set<String> texts = new HashSet<>();
        repeated |= texts(search).stream().anyMatch(text -> !text.isBlank() && !texts.add(text));
        if (repeated) {
            insights.add(new Insight(Rule.DUPLICATE_CHUNKS, search.spanId(),
                    "Duplicate chunks: " + search.name() + " returned the same document more than once", 0));
        }
    }

    private static void chunksMissingFromPrompt(TraceView.Row search, TraceView.Row chat, List<Insight> insights) {
        String prompt = wholePrompt(chat);
        List<String> texts = chunkTexts(search);
        if (prompt == null || texts.isEmpty()) {
            return;
        }
        long missing = texts.stream().filter(text -> !prompt.contains(text)).count();
        if (missing > 0) {
            insights.add(new Insight(Rule.CHUNK_NOT_IN_PROMPT, search.spanId(), "Chunk not in prompt: " + missing + " of "
                    + texts.size() + " chunks from " + search.name() + " did not reach " + chat.name()
                    + ". A reranker may have removed them, or a template rewrote them", 0));
        }
    }

    private static void repeatedChunks(TraceView.Row chat, Set<String> chunks, List<Insight> insights) {
        String prompt = chunks.isEmpty() ? null : wholePrompt(chat);
        if (prompt == null) {
            return;
        }
        boolean repeated = chunks.stream()
                .filter(chunk -> chunk.length() >= MIN_REPEATED_CHUNK_CHARS)
                .anyMatch(chunk -> prompt.indexOf(chunk) != prompt.lastIndexOf(chunk));
        if (repeated) {
            insights.add(new Insight(Rule.DUPLICATE_CHUNKS, chat.spanId(),
                    "Duplicate chunks: the same chunk reached the prompt of " + chat.name() + " twice", 0));
        }
    }

    private static void answerCutOff(TraceView.Row chat, List<Insight> insights) {
        if (!(AiJson.parse(chat.attributes().get("gen_ai.response.finish_reasons")) instanceof List<?> reasons)) {
            return;
        }
        boolean cut = reasons.stream()
                .anyMatch(reason -> "length".equalsIgnoreCase(String.valueOf(reason))
                        || "max_tokens".equalsIgnoreCase(String.valueOf(reason)));
        if (cut) {
            insights.add(new Insight(Rule.ANSWER_CUT_OFF, chat.spanId(), "Answer cut off: " + chat.name()
                    + " stopped at its output token limit, so the answer is incomplete", 0));
        }
    }

    private static void contextNearLimit(TraceView.Row chat, List<Insight> insights) {
        Double limit = number(chat.attributes().get(CONTEXT_LIMIT));
        Double tokens = number(chat.attributes().get("gen_ai.usage.input_tokens"));
        if (limit != null && tokens != null && tokens > limit * CONTEXT_WARNING_SHARE) {
            insights.add(new Insight(Rule.CONTEXT_NEAR_LIMIT, chat.spanId(), String.format(Locale.ROOT,
                    "Context near limit: %s used %.0f input tokens, %.0f%% of causeline.ai.context-limit",
                    chat.name(), tokens, tokens * 100 / limit), 0));
        }
    }

    private static void repeatedEmbeddings(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, List<TraceView.Row>> byInput = new LinkedHashMap<>();
        for (TraceView.Row row : rows) {
            String input = row.attributes().get(EMBEDDING_INPUT);
            if (row.kind() == SpanKind.AI_MODEL && AiJson.parse(input) != null) {
                byInput.computeIfAbsent(input, k -> new ArrayList<>()).add(row);
            }
        }
        byInput.values().stream().filter(group -> group.size() > 1).forEach(group -> {
            TraceView.Row first = group.getFirst();
            insights.add(new Insight(Rule.REPEATED_EMBEDDING, first.spanId(), "Repeated embedding: the same input went to "
                    + first.name() + " " + group.size() + " times in this trace", 0));
        });
    }

    private static List<String> texts(TraceView.Row search) {
        return AiJson.objects(AiJson.parse(search.attributes().get(DOCUMENT_CONTENT))).stream()
                .map(content -> content.get("text"))
                .filter(String.class::isInstance)
                .map(text -> normalize((String) text))
                .toList();
    }

    private static List<String> chunkTexts(TraceView.Row search) {
        return texts(search).stream().filter(text -> !text.isBlank()).toList();
    }

    /**
     * The prompt as text, or null when it can't settle a match: not captured, withheld, or cut, in
     * which case a chunk may lie past the cut. Lengths are compared before line endings are normalised.
     */
    private static String wholePrompt(TraceView.Row chat) {
        List<String> parts = new ArrayList<>();
        AiJson.objects(AiJson.parse(chat.attributes().get("gen_ai.system_instructions")))
                .forEach(part -> addContent(part, parts));
        AiJson.objects(AiJson.parse(chat.attributes().get("gen_ai.input.messages")))
                .forEach(message -> AiJson.objects(message.get("parts")).forEach(part -> addContent(part, parts)));
        if (parts.isEmpty()) {
            return null;
        }
        int kept = parts.stream().mapToInt(String::length).sum();
        Double full = number(chat.attributes().get(INPUT_CHARS));
        return full != null && full > kept ? null : normalize(String.join("\n", parts));
    }

    private static void addContent(Map<String, Object> part, List<String> parts) {
        if (part.get("content") instanceof String content) {
            parts.add(content);
        }
    }

    /** Spring AI joins chunks with the platform's line separator, so CRLF must not stop a match. */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static Double number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
