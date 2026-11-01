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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;

/**
 * Adds prompts and answers to Spring AI's model spans. Its observations hold only metadata, and its
 * {@code log-prompt} settings write to logs, so the text is read from the observation's context.
 *
 * <p>A filter runs before tracing ends the span. It runs on the application's thread or a model
 * client's stream thread for every observation, so it stays cheap, and a failure only loses the value.
 */
public final class ModelCaptureFilter implements ObservationFilter {

    private static final Logger log = LoggerFactory.getLogger(ModelCaptureFilter.class);

    private final Ai settings;
    private final AiHints hints;
    private final AiText text;

    public ModelCaptureFilter(Ai settings, AiHints hints, SensitiveData userBlocked) {
        this.settings = settings;
        this.hints = hints;
        this.text = new AiText(userBlocked);
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        boolean chat = context instanceof ChatModelObservationContext;
        if (!chat && !(context instanceof EmbeddingModelObservationContext)) {
            return context;
        }
        context.addHighCardinalityKeyValue(KeyValue.of(SpanMapper.KIND_ATTRIBUTE, SpanKind.AI_MODEL.name()));
        try {
            if (context instanceof ChatModelObservationContext call) {
                addChat(call);
            } else if (context instanceof EmbeddingModelObservationContext call) {
                addEmbedding(call);
            }
        } catch (RuntimeException | StackOverflowError e) {
            log.debug("Could not read a model call", e);
        }
        return context;
    }

    private void addChat(ChatModelObservationContext call) {
        if (hints.contextLimit() != null && hints.contextLimit() > 0) {
            call.addHighCardinalityKeyValue(
                    KeyValue.of(AiAttributes.CAPTURED_CONTEXT_LIMIT, hints.contextLimit().toString()));
        }
        Prompt prompt = call.getRequest();
        if (settings.prompts() && prompt != null) {
            addPrompt(call, prompt.getInstructions());
        }
        ChatResponse response = call.getResponse();
        if (settings.completions() && response != null) {
            addCompletion(call, response.getResults());
        }
    }

    private void addPrompt(Observation.Context context, List<Message> messages) {
        List<Message> system = messages.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM).toList();
        List<Message> others = messages.stream().filter(m -> m.getMessageType() != MessageType.SYSTEM).toList();
        int chars = 0;
        // The newest message holds the question and its context, so it is the last to lose space.
        int budget = AiText.MAX_TEXT_CHARS;
        List<String> contents = new ArrayList<>();
        for (int i = others.size() - 1; i >= 0; i--) {
            String content = nonNull(others.get(i).getText());
            chars += content.length();
            String kept = text.clean(content, budget);
            budget = Math.max(0, budget - kept.length());
            contents.addFirst(kept);
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < others.size(); i++) {
            message(json, i > 0, others.get(i).getMessageType().name().toLowerCase(Locale.ROOT), contents.get(i), null);
        }
        context.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_INPUT, json.append(']').toString()));

        if (!system.isEmpty()) {
            StringBuilder instructions = new StringBuilder("[");
            int left = AiText.MAX_TEXT_CHARS;
            for (Message message : system) {
                String content = nonNull(message.getText());
                chars += content.length();
                String kept = text.clean(content, left);
                left = Math.max(0, left - kept.length());
                instructions.append(instructions.length() > 1 ? ",{" : "{").append("\"type\":\"text\",\"content\":");
                AiText.string(instructions, kept);
                instructions.append('}');
            }
            context.addHighCardinalityKeyValue(
                    KeyValue.of(AiAttributes.CAPTURED_SYSTEM, instructions.append(']').toString()));
        }
        context.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_INPUT_CHARS, Integer.toString(chars)));
    }

    private void addCompletion(Observation.Context context, List<Generation> generations) {
        StringBuilder json = new StringBuilder("[");
        int chars = 0;
        int budget = AiText.MAX_TEXT_CHARS;
        boolean first = true;
        for (Generation generation : generations) {
            String content = nonNull(generation.getOutput().getText());
            chars += content.length();
            String kept = text.clean(content, budget);
            budget = Math.max(0, budget - kept.length());
            message(json, !first, "assistant", kept, generation.getMetadata().getFinishReason());
            first = false;
        }
        context.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_OUTPUT, json.append(']').toString()));
        context.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_OUTPUT_CHARS, Integer.toString(chars)));
    }

    private void addEmbedding(EmbeddingModelObservationContext call) {
        // The input is a question, or a whole document while ingesting.
        if (!settings.prompts() || !settings.documents() || call.getRequest() == null) {
            return;
        }
        StringBuilder json = new StringBuilder("[");
        int chars = 0;
        int budget = AiText.MAX_TEXT_CHARS;
        for (String input : call.getRequest().getInstructions()) {
            String content = nonNull(input);
            chars += content.length();
            String kept = text.clean(content, budget);
            budget = Math.max(0, budget - kept.length());
            if (json.length() > 1) {
                json.append(',');
            }
            AiText.string(json, kept);
        }
        call.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_EMBEDDING, json.append(']').toString()));
        call.addHighCardinalityKeyValue(KeyValue.of(AiAttributes.CAPTURED_INPUT_CHARS, Integer.toString(chars)));
    }

    /** One entry in OpenTelemetry's message format. */
    private static void message(StringBuilder json, boolean comma, String role, String content, String finishReason) {
        json.append(comma ? ",{" : "{").append("\"role\":");
        AiText.string(json, role);
        json.append(",\"parts\":[{\"type\":\"text\",\"content\":");
        AiText.string(json, content);
        json.append("}]");
        if (finishReason != null) {
            json.append(",\"finish_reason\":");
            AiText.string(json, finishReason);
        }
        json.append('}');
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
