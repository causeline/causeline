// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Asks the shop's RAG assistant a question, in this app or in the Python service. */
@RestController
class AssistantController {

    record Question(String question, String mode) {
    }

    record Answer(String answer) {
    }

    private final AssistantService assistant;
    private final PythonAssistant python;

    AssistantController(AssistantService assistant, PythonAssistant python) {
        this.assistant = assistant;
        this.python = python;
    }

    @PostMapping("/api/ask")
    Answer ask(@RequestBody Question question) {
        return new Answer(assistant.ask(question.question(), question.mode()));
    }

    @PostMapping("/api/ask/python")
    Answer askPython(@RequestBody Question question) {
        return new Answer(python.ask(question.question(), question.mode()));
    }
}
