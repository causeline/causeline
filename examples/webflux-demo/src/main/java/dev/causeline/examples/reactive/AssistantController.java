// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** A small RAG assistant that streams its answer, with Spring AI and the fake model. */
@RestController
class AssistantController {

    record Question(String question) {
    }

    private final ChatClient chat;
    private final VectorStore documents;
    private final FakeModelServer model;
    private final QuestionAnswerAdvisor advisor;
    private boolean seeded;

    AssistantController(ChatClient.Builder chat, VectorStore documents, FakeModelServer model) {
        this.chat = chat.build();
        this.documents = documents;
        this.model = model;
        this.advisor = QuestionAnswerAdvisor.builder(documents)
                .searchRequest(SearchRequest.builder().topK(3).build())
                .build();
    }

    @PostMapping(path = "/api/ask", produces = MediaType.TEXT_PLAIN_VALUE)
    Flux<String> ask(@RequestBody Question question) {
        return Mono.fromRunnable(this::seed).subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.defer(() -> chat.prompt().user(question.question()).advisors(advisor).stream().content()));
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
