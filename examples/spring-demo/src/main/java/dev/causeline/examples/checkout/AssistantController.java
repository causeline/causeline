// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Asks the shop's RAG assistant a question. */
@RestController
class AssistantController {

    record Question(String question, String mode) {
    }

    record Answer(String answer) {
    }

    private final AssistantService assistant;

    AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/api/ask")
    Answer ask(@RequestBody Question question) {
        return new Answer(assistant.ask(question.question(), question.mode()));
    }
}
