// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.ai;

import dev.causeline.core.SpanKind;
import dev.causeline.spring.autoconfigure.CauselineProperties.Ai;
import dev.causeline.spring.autoconfigure.CauselineProperties.AiHints;
import dev.causeline.spring.internal.capture.SensitiveData;
import dev.causeline.spring.internal.tracing.SpanMapper;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

/**
 * Adds the query and the returned documents to Spring AI's vector search spans. Scores come from
 * the documents themselves; the observation has no key for them. Same rules as
 * {@link ModelCaptureFilter}. Adding and deleting documents stay ordinary spans.
 */
public final class VectorSearchCaptureFilter implements ObservationFilter {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchCaptureFilter.class);

    private final Ai settings;
    private final AiHints hints;
    private final AiText text;

    public VectorSearchCaptureFilter(Ai settings, AiHints hints, SensitiveData userBlocked) {
        this.settings = settings;
        this.hints = hints;
        this.text = new AiText(userBlocked);
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof VectorStoreObservationContext search)
                || !VectorStoreObservationContext.Operation.QUERY.value().equals(search.getOperationName())) {
            return context;
        }
        context.addHighCardinalityKeyValue(KeyValue.of(SpanMapper.KIND_ATTRIBUTE, SpanKind.AI_RETRIEVAL.name()));
        try {
            add(search);
        } catch (RuntimeException | StackOverflowError e) {
            log.debug("Could not read a vector search", e);
        }
        return context;
    }

    private void add(VectorStoreObservationContext search) {
        if (hints.lowScore() > 0) {
            search.addHighCardinalityKeyValue(
                    KeyValue.of(AiAttributes.CAPTURED_LOW_SCORE, Double.toString(hints.lowScore())));
        }
        SearchRequest request = search.getQueryRequest();
        if (settings.documents() && request != null) {
            search.addHighCardinalityKeyValue(
                    KeyValue.of(AiAttributes.CAPTURED_QUERY, text.clean(request.getQuery(), AiText.MAX_TEXT_CHARS)));
            if (request.getFilterExpression() != null) {
                search.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_FILTER,
                        text.clean(request.getFilterExpression().toString(), AiText.MAX_DOCUMENT_CHARS)));
            }
        }
        List<Document> documents = search.getQueryResponse();
        if (documents == null) {
            return;
        }
        search.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_COUNT, Integer.toString(documents.size())));
        List<Document> kept = documents.subList(0, Math.min(documents.size(), AiText.MAX_DOCUMENTS));
        StringBuilder summary = new StringBuilder("[");
        StringBuilder content = new StringBuilder("[");
        for (int i = 0; i < kept.size(); i++) {
            Document document = kept.get(i);
            String body = document.getText() == null ? "" : document.getText();
            summary.append(i > 0 ? ",{" : "{").append("\"id\":");
            AiText.string(summary, AiText.cap(document.getId(), AiText.MAX_ID_CHARS));
            summary.append(",\"rank\":").append(i + 1).append(",\"chars\":").append(body.length());
            Double score = document.getScore();
            if (score != null && Double.isFinite(score)) {
                summary.append(",\"score\":").append(score);
            }
            summary.append('}');

            content.append(i > 0 ? ",{" : "{").append("\"text\":");
            AiText.string(content, text.clean(body, AiText.MAX_DOCUMENT_CHARS));
            if (document.getMetadata().get("source") instanceof String source) {
                content.append(",\"source\":");
                AiText.string(content, text.clean(source, AiText.MAX_ID_CHARS));
            }
            content.append('}');
        }
        search.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_DOCUMENTS, summary.append(']').toString()));
        if (settings.documents()) {
            search.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_CONTENT, content.append(']').toString()));
        }
    }
}
