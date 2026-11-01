// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for a model provider, speaking the OpenAI HTTP API so the real Spring AI client and its
 * observations run with no key and no network. Like the fake payment provider it runs on its own
 * port and reports no spans. Embeddings are word counts, so similar text really does score higher.
 */
@Component
class FakeModelServer implements AutoCloseable {

    private static final int DIMENSIONS = 64;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int port;
    private HttpServer server;

    FakeModelServer(@Value("${demo.model.port:8090}") int port) {
        this.port = port;
    }

    /** Started on first use: only the assistant needs it. */
    synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the fake model on port " + port, e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/embeddings", exchange -> {
            try (exchange) {
                reply(exchange, embeddings(JSON.readTree(exchange.getRequestBody().readAllBytes())));
            }
        });
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                if (request.path("stream").asBoolean(false)) {
                    stream(exchange);
                } else {
                    reply(exchange, completion());
                }
            }
        });
        server.start();
    }

    private static String completion() {
        return "{\"id\":\"fake-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fake-chat\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(answer()) + "},"
                + "\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":12,\"total_tokens\":132}}";
    }

    private static String answer() {
        return "According to the shop's policy, refunds are issued within 14 days.";
    }

    private static void stream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        StringBuilder events = new StringBuilder();
        for (String word : answer().split("(?<= )")) {
            events.append("data: ").append(chunk("{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(word) + "}", "null"))
                    .append("\n\n");
        }
        events.append("data: ").append(chunk("{}", "\"stop\"")).append("\n\n");
        events.append("data: {\"id\":\"fake-2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake-chat\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":12,\"total_tokens\":132}}\n\n");
        events.append("data: [DONE]\n\n");
        exchange.getResponseBody().write(events.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String chunk(String delta, String finishReason) {
        return "{\"id\":\"fake-2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake-chat\",\"choices\":"
                + "[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + finishReason + "}]}";
    }

    private static String embeddings(JsonNode request) {
        JsonNode input = request.path("input");
        List<String> texts = new ArrayList<>();
        if (input.isArray()) {
            input.forEach(text -> texts.add(text.asString()));
        } else {
            texts.add(input.asString());
        }
        List<String> data = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            data.add("{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":\"" + vector(texts.get(i)) + "\"}");
        }
        return "{\"object\":\"list\",\"data\":[" + String.join(",", data) + "],\"model\":\"fake-embed\","
                + "\"usage\":{\"prompt_tokens\":8,\"total_tokens\":8}}";
    }

    /** The OpenAI client asks for base64: little-endian floats. */
    private static String vector(String text) {
        float[] counts = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (word.length() > 3) {
                counts[Math.floorMod(word.hashCode(), DIMENSIONS)]++;
            }
        }
        ByteBuffer bytes = ByteBuffer.allocate(DIMENSIONS * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float count : counts) {
            bytes.putFloat(count + 0.01f);
        }
        return Base64.getEncoder().encodeToString(bytes.array());
    }

    private static void reply(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
