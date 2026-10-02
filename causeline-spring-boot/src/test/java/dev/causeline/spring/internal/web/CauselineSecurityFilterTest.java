// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.spring.autoconfigure.TestProperties;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CauselineSecurityFilterTest {

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final CauselineSecurityFilter filter = new CauselineSecurityFilter(
            TestProperties.bind(Map.of(
                    "allowed-hosts", "devbox.internal",
                    "ingest.allowed-origins", "http://localhost:3000",
                    "ingest.max-requests-per-minute", "3")),
            AccessToken.of("secret-token"),
            clock::get);

    @Test
    void readApiRequiresTheToken() throws Exception {
        assertThat(run(get("/causeline/api/traces")).getStatus()).isEqualTo(401);

        MockHttpServletRequest withToken = get("/causeline/api/traces");
        withToken.addHeader(CauselineSecurityFilter.TOKEN_HEADER, "secret-token");
        assertThat(run(withToken).getStatus()).isEqualTo(200);

        MockHttpServletRequest wrongToken = get("/causeline/api/traces");
        wrongToken.addHeader(CauselineSecurityFilter.TOKEN_HEADER, "guess");
        assertThat(run(wrongToken).getStatus()).isEqualTo(401);
    }

    @Test
    void staticUiNeedsNoToken() throws Exception {
        assertThat(run(get("/causeline/index.html")).getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsUnknownHostsToStopDnsRebinding() throws Exception {
        MockHttpServletRequest rebound = get("/causeline/index.html");
        rebound.setServerName("attacker.example");
        assertThat(run(rebound).getStatus()).isEqualTo(403);

        MockHttpServletRequest allowed = get("/causeline/index.html");
        allowed.setServerName("devbox.internal");
        assertThat(run(allowed).getStatus()).isEqualTo(200);
    }

    @Test
    void ingestAcceptsSameOriginAndAllowedOriginsOnly() throws Exception {
        assertThat(run(ingest("http://localhost:8080", 100)).getStatus()).isEqualTo(200);
        assertThat(run(ingest("http://localhost:3000", 100)).getStatus()).isEqualTo(200);
        assertThat(run(ingest("https://evil.example", 100)).getStatus()).isEqualTo(403);
        assertThat(run(ingest(null, 100)).getStatus()).isEqualTo(200);
    }

    @Test
    void ingestAcceptsLocalDevServersBehindAHostRewritingProxy() throws Exception {
        // Vite on :5173 proxies to :8080 and rewrites Host; the Origin still says :5173.
        assertThat(run(ingest("http://localhost:5173", 100)).getStatus()).isEqualTo(200);
        assertThat(run(ingest("http://127.0.0.1:5173", 100)).getStatus()).isEqualTo(200);

        MockHttpServletRequest remoteServer = ingest("http://localhost:5173", 100);
        remoteServer.setServerName("devbox.internal");
        assertThat(run(remoteServer).getStatus()).isEqualTo(403);
    }

    @Test
    void ingestRejectsOversizedAndUnsizedUploads() throws Exception {
        assertThat(run(ingest(null, 262_145)).getStatus()).isEqualTo(413);

        MockHttpServletRequest chunked = ingest(null, 0);
        chunked.setContent(null);
        assertThat(run(chunked).getStatus()).isEqualTo(411);
    }

    @Test
    void ingestIsRateLimitedPerClientPerMinute() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(run(ingest(null, 10)).getStatus()).isEqualTo(200);
        }
        assertThat(run(ingest(null, 10)).getStatus()).isEqualTo(429);

        clock.addAndGet(60_000);
        assertThat(run(ingest(null, 10)).getStatus()).isEqualTo(200);
    }

    @Test
    void leavesOtherPathsAlone() throws Exception {
        MockHttpServletRequest app = get("/api/orders");
        app.setServerName("attacker.example");
        assertThat(run(app).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServerName("localhost");
        request.setServerPort(8080);
        return request;
    }

    private static MockHttpServletRequest ingest(String origin, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/causeline/api/spans");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setContent(new byte[bytes]);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }
}
