// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.ai;

import dev.causeline.spring.internal.capture.RequestCaptureFilter;
import java.util.Map;
import java.util.Set;

/**
 * Attribute names for AI spans. This class references no Spring AI type, so {@code SpanMapper} and
 * {@code SpanRedactor} can use it in an application without Spring AI.
 *
 * <p>The RAG view reads only these attributes, so another source of spans can produce the same view.
 */
public final class AiAttributes {

    private static final String CAPTURE = RequestCaptureFilter.PREFIX + "ai.";

    public static final String INPUT_MESSAGES = "gen_ai.input.messages";
    public static final String OUTPUT_MESSAGES = "gen_ai.output.messages";
    public static final String SYSTEM_INSTRUCTIONS = "gen_ai.system_instructions";
    public static final String EMBEDDING_INPUT = "causeline.ai.embedding.input";
    public static final String SEARCH_QUERY = "db.vector.query.content";
    public static final String SEARCH_FILTER = "db.vector.query.filter";
    /** ID, rank, score and text length of each document returned. Kept on export. */
    public static final String DOCUMENTS = "causeline.ai.documents";
    /** The text and source of each document, in the same order as {@link #DOCUMENTS}. */
    public static final String DOCUMENT_CONTENT = "causeline.ai.documents.content";
    public static final String DOCUMENT_COUNT = "causeline.ai.documents.count";
    /** The full length of the input before capping, so the UI can say how much was cut. Kept on export. */
    public static final String INPUT_CHARS = "causeline.ai.input.chars";
    public static final String OUTPUT_CHARS = "causeline.ai.output.chars";
    /** The limits the hints apply, on the span so a trace is judged by the settings that recorded it. */
    public static final String LOW_SCORE = "causeline.ai.low_score";
    public static final String CONTEXT_LIMIT = "causeline.ai.context_limit";

    /** Free text that can hold anything: withheld whole on export, since scrubbing patterns isn't enough. */
    public static final Set<String> TEXT = Set.of(INPUT_MESSAGES, OUTPUT_MESSAGES, SYSTEM_INSTRUCTIONS, EMBEDDING_INPUT,
            SEARCH_QUERY, SEARCH_FILTER, DOCUMENT_CONTENT);

    public static final String WITHHELD = "[withheld on export]";

    static final String CAPTURED_INPUT = CAPTURE + "input";
    static final String CAPTURED_OUTPUT = CAPTURE + "output";
    static final String CAPTURED_SYSTEM = CAPTURE + "system";
    static final String CAPTURED_EMBEDDING = CAPTURE + "embedding";
    static final String CAPTURED_QUERY = CAPTURE + "query";
    static final String CAPTURED_FILTER = CAPTURE + "filter";
    static final String CAPTURED_DOCUMENTS = CAPTURE + "documents";
    static final String CAPTURED_CONTENT = CAPTURE + "content";
    static final String CAPTURED_COUNT = CAPTURE + "count";
    static final String CAPTURED_INPUT_CHARS = CAPTURE + "input.chars";
    static final String CAPTURED_OUTPUT_CHARS = CAPTURE + "output.chars";
    static final String CAPTURED_LOW_SCORE = CAPTURE + "low_score";
    static final String CAPTURED_CONTEXT_LIMIT = CAPTURE + "context_limit";

    /** Only the capture filters write these keys, so the capture settings hold whatever Spring AI records. */
    public static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry(CAPTURED_INPUT, INPUT_MESSAGES),
            Map.entry(CAPTURED_OUTPUT, OUTPUT_MESSAGES),
            Map.entry(CAPTURED_SYSTEM, SYSTEM_INSTRUCTIONS),
            Map.entry(CAPTURED_EMBEDDING, EMBEDDING_INPUT),
            Map.entry(CAPTURED_QUERY, SEARCH_QUERY),
            Map.entry(CAPTURED_FILTER, SEARCH_FILTER),
            Map.entry(CAPTURED_DOCUMENTS, DOCUMENTS),
            Map.entry(CAPTURED_CONTENT, DOCUMENT_CONTENT),
            Map.entry(CAPTURED_COUNT, DOCUMENT_COUNT),
            Map.entry(CAPTURED_INPUT_CHARS, INPUT_CHARS),
            Map.entry(CAPTURED_OUTPUT_CHARS, OUTPUT_CHARS),
            Map.entry(CAPTURED_LOW_SCORE, LOW_SCORE),
            Map.entry(CAPTURED_CONTEXT_LIMIT, CONTEXT_LIMIT));

    private AiAttributes() {
    }
}
