// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.spring.autoconfigure.TestProperties;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** The WebFlux guard applies the same rules as the servlet one ({@link CauselineSecurityFilterTest}). */
class CauselineSecurityWebFilterTest {

    private final CauselineSecurityWebFilter filter = new CauselineSecurityWebFilter(
            TestProperties.bind(Map.of("ingest.allowed-origins", "http://localhost:3000")),
            AccessToken.of("secret-token"), () -> 1_000_000L);

    @Test
    void readApiRequiresTheToken() {
        assertThat(run(MockServerHttpRequest.get("http://localhost:8080/causeline/api/traces"))).isEqualTo(401);
        assertThat(run(MockServerHttpRequest.get("http://localhost:8080/causeline/api/traces")
                .header(CauselineGuard.TOKEN_HEADER, "secret-token"))).isEqualTo(200);
    }

    @Test
    void rejectsUnknownHostsToStopDnsRebinding() {
        assertThat(run(MockServerHttpRequest.get("http://attacker.example:8080/causeline/index.html"))).isEqualTo(403);
        assertThat(run(MockServerHttpRequest.get("http://localhost:8080/causeline/index.html"))).isEqualTo(200);
    }

    @Test
    void ingestChecksOriginAndSize() {
        assertThat(run(MockServerHttpRequest.post("http://localhost:8080/causeline/api/spans")
                .header("Origin", "https://evil.example").contentLength(10))).isEqualTo(403);
        assertThat(run(MockServerHttpRequest.post("http://localhost:8080/causeline/api/spans")
                .header("Origin", "http://localhost:3000"))).isEqualTo(411);
        assertThat(run(MockServerHttpRequest.post("http://localhost:8080/causeline/api/spans")
                .header("Origin", "http://localhost:5173").contentLength(10))).isEqualTo(200);
    }

    @Test
    void applicationRequestsPassUntouched() {
        assertThat(run(MockServerHttpRequest.get("http://attacker.example/api/orders"))).isEqualTo(200);
    }

    /** The response status, or 200 when the request reached the rest of the chain. */
    private int run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        return passed.get() ? 200 : status == null ? 0 : status.value();
    }
}
