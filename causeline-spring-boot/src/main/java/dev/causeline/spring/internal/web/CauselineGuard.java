// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The rules that guard everything under {@code /causeline}, whatever the web stack:
 * <ul>
 *   <li>the Host header must be localhost or explicitly allowed (defeats DNS rebinding);</li>
 *   <li>the read API needs the access token in {@value #TOKEN_HEADER};</li>
 *   <li>span uploads need no token (they can only add spans) but are limited by origin, size and rate.</li>
 * </ul>
 * The UI's static files need no token: they contain no trace data.
 */
public final class CauselineGuard {

    public static final String TOKEN_HEADER = "X-Causeline-Token";

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /** What the guard needs to know about a request. {@code contentLength} is -1 when unknown. */
    public record Request(String serverName, int serverPort, String path, String origin, long contentLength,
            String remoteAddress, String token) {
    }

    /** Why a request was turned away. */
    public record Rejection(int status, String message) {

        public String json() {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }

    private final AccessToken token;
    private final Set<String> allowedHosts;
    private final Set<String> allowedOrigins;
    private final long maxBytes;
    private final int maxRequestsPerMinute;
    private final LongSupplier clockMillis;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public CauselineGuard(CauselineProperties properties, AccessToken token, LongSupplier clockMillis) {
        this.token = token;
        this.allowedHosts = new HashSet<>(LOCAL_HOSTS);
        properties.allowedHosts().forEach(h -> allowedHosts.add(h.toLowerCase(Locale.ROOT)));
        this.allowedOrigins = Set.copyOf(properties.ingest().allowedOrigins());
        this.maxBytes = properties.ingest().maxBytes();
        this.maxRequestsPerMinute = properties.ingest().maxRequestsPerMinute();
        this.clockMillis = clockMillis;
    }

    /** Null when the request may go on. Only call it for Causeline paths. */
    public Rejection check(Request request) {
        if (request.serverName() == null || !allowedHosts.contains(request.serverName().toLowerCase(Locale.ROOT))) {
            return new Rejection(403, "Host not allowed. Add it to causeline.allowed-hosts if this is intended.");
        }
        String path = request.path();
        if (path.equals(CauselinePaths.INGEST_PATH)) {
            String origin = request.origin();
            if (origin != null && !isSameOrigin(origin, request) && !isLocalToLocal(origin, request)
                    && !allowedOrigins.contains(origin)) {
                return new Rejection(403, "Origin not allowed to send spans.");
            }
            if (request.contentLength() < 0) {
                return new Rejection(411, "Content-Length required.");
            }
            if (request.contentLength() > maxBytes) {
                return new Rejection(413, "Span upload too large.");
            }
            if (!tryAcquire(request.remoteAddress())) {
                return new Rejection(429, "Too many span uploads.");
            }
        } else if (path.startsWith(CauselinePaths.API_PREFIX) && !token.matches(request.token())) {
            return new Rejection(401,
                    "Causeline access token missing or wrong. Open the link printed in the application log.");
        }
        return null;
    }

    /** Fixed one-minute window per client address. */
    private boolean tryAcquire(String client) {
        long now = clockMillis.getAsLong();
        if (windows.size() > 10_000) {
            windows.clear(); // bound memory if something floods us from many addresses
        }
        long[] window = windows.compute(String.valueOf(client), (k, w) -> {
            if (w == null || now - w[0] >= 60_000) {
                return new long[] {now, 1};
            }
            w[1]++;
            return w;
        });
        return window[1] <= maxRequestsPerMinute;
    }

    private static boolean isSameOrigin(String origin, Request request) {
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            return request.serverName().equalsIgnoreCase(uri.getHost()) && request.serverPort() == port;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * A page on any localhost port posting to a localhost server. Dev proxies (Vite, CRA, Next)
     * rewrite the Host header, so a page on :5173 reaches :8080 looking cross-origin. A website on
     * the internet can never have a localhost origin, and ingest can only add spans.
     */
    private static boolean isLocalToLocal(String origin, Request request) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    && LOCAL_HOSTS.contains(request.serverName().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
