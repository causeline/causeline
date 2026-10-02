// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.TestProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayServiceTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN = "00f067aa0ba902b7";

    private final AtomicReference<Headers> received = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private HttpServer target;
    private final ReplayStore records = new ReplayStore(10);

    @BeforeEach
    void start() throws Exception {
        target = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        target.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        target.start();
        records.put(new ReplayRecord(TRACE, SPAN, "POST", "/api/orders", "page=2",
                Map.of("Authorization", "Bearer user-token", "Cookie", "SESSION=abc", "Content-Type", "application/json",
                        "X-Tenant-Id", "acme"),
                "{\"password\":\"hunter2\"}".getBytes(StandardCharsets.UTF_8), true, true, false));
    }

    @AfterEach
    void stop() {
        target.stop(0);
    }

    @Test
    void resendsTheOriginalCredentialsByDefault() {
        replay(Map.of()).replay(TRACE, SPAN, "qa", true, null);

        assertThat(received.get().getFirst("Authorization")).isEqualTo("Bearer user-token");
        assertThat(received.get().getFirst("Cookie")).isEqualTo("SESSION=abc");
        assertThat(received.get().getFirst("X-Tenant-Id")).isEqualTo("acme");
        assertThat(body.get()).isEqualTo("{\"password\":\"hunter2\"}");
    }

    @Test
    void aTargetAuthProfileReplacesTheOriginalAuthorization() {
        replay(Map.of("replay.targets.qa.auth.type", "bearer", "replay.targets.qa.auth.token", "qa-token"))
                .replay(TRACE, SPAN, "qa", true, null);

        assertThat(received.get().get("Authorization")).containsExactly("Bearer qa-token");
        assertThat(received.get().getFirst("Cookie")).isEqualTo("SESSION=abc");
    }

    @Test
    void originalCredentialsCanBeWithheld() {
        replay(Map.of("replay.send-original-credentials", "false")).replay(TRACE, SPAN, "qa", true, null);

        assertThat(received.get().containsKey("Authorization")).isFalse();
        assertThat(received.get().containsKey("Cookie")).isFalse();
        assertThat(received.get().getFirst("X-Tenant-Id")).isEqualTo("acme");
    }

    private ReplayService replay(Map<String, String> settings) {
        Map<String, String> all = new HashMap<>(settings);
        all.put("replay.targets.qa.base-url", "http://localhost:" + target.getAddress().getPort());
        return new ReplayService(records, new TraceStore(), TestProperties.bind(all).replay(), Optional.empty(), () -> 0);
    }
}
