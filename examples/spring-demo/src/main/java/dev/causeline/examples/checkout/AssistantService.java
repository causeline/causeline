// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.annotation.Observed;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/** A small RAG assistant: Spring AI searches the shop's documents and asks the fake model. */
@Service
class AssistantService {

    private final ChatClient chat;
    private final VectorStore documents;
    private final FakeModelServer model;
    private final QuestionAnswerAdvisor advisor;
    private boolean seeded;

    AssistantService(ChatClient.Builder chat, VectorStore documents, FakeModelServer model) {
        this.chat = chat.build();
        this.documents = documents;
        this.model = model;
        this.advisor = QuestionAnswerAdvisor.builder(documents)
                .searchRequest(SearchRequest.builder().topK(3).build())
                .build();
    }

    /**
     * @param mode {@code cut-off} sets a tight output limit, {@code reranked} keeps only the best
     *             chunk, and anything else is a plain question
     */
    @Observed
    public String ask(String question, String mode) {
        seed();
        return switch (mode == null ? "" : mode) {
            case "cut-off" -> chat.prompt().user(question).advisors(advisor)
                    .options(ChatOptions.builder().maxTokens(5)).call().content();
            case "reranked" -> reranked(question);
            default -> chat.prompt().user(question).advisors(advisor).call().content();
        };
    }

    /** Stands in for a reranker: three chunks come back and only one reaches the model. */
    private String reranked(String question) {
        List<Document> found = documents.similaritySearch(SearchRequest.builder().query(question).topK(3).build());
        String context = found.isEmpty() ? "" : found.getFirst().getText();
        return chat.prompt().user(question + "\n\nContext:\n" + context).call().content();
    }

    /** On its own thread, so ingesting isn't part of the first question's trace. */
    private synchronized void seed() {
        if (seeded) {
            return;
        }
        model.start();
        Thread ingest = Thread.ofVirtual().start(() -> documents.add(List.of(
                new Document("refunds", "Refunds are issued within 14 days of delivery.", Map.of("source", "policy.md")),
                new Document("receipts", "A refund needs the original receipt.", Map.of("source", "policy.md")),
                new Document("shipping", "Standard shipping takes three working days.", Map.of("source", "shipping.md")),
                new Document("hours", "Support answers on weekdays from nine to five.", Map.of("source", "support.md")))));
        try {
            ingest.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        seeded = true;
    }
}
