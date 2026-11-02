// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import dev.causeline.examples.checkout.AssistantController.Answer;
import dev.causeline.examples.checkout.AssistantController.Question;
import io.micrometer.observation.annotation.Observed;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Asks the assistant in {@code examples/python-demo}, which uses this app's fake model. */
@Component
class PythonAssistant {

    private final RestClient.Builder builder;
    private final FakeModelServer model;
    private final String url;

    PythonAssistant(RestClient.Builder builder, FakeModelServer model, @Value("${demo.python.url}") String url) {
        this.builder = builder;
        this.model = model;
        this.url = url;
    }

    @Observed
    public String ask(String question, String mode) {
        model.start();
        try {
            return builder.baseUrl(url).build()
                    .post()
                    .uri("/ask")
                    .body(new Question(question, mode))
                    .retrieve()
                    .body(Answer.class)
                    .answer();
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Start examples/python-demo first", e);
        }
    }
}
