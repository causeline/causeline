// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards everything under {@code /causeline}:
 * <ul>
 *   <li>the Host header must be localhost or explicitly allowed (defeats DNS rebinding);</li>
 *   <li>the read API needs the access token in {@value #TOKEN_HEADER};</li>
 *   <li>span uploads need no token (they can only add spans) but are limited by origin, size and rate.</li>
 * </ul>
 * The UI's static files need no token: they contain no trace data.
 */
public final class CauselineSecurityFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Causeline-Token";

    static final String API_PREFIX = CauselineWebConfiguration.BASE_PATH + "/api/";
    static final String INGEST_PATH = CauselineWebConfiguration.BASE_PATH + "/api/spans";

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final AccessToken token;
    private final Set<String> allowedHosts;
    private final Set<String> allowedOrigins;
    private final long maxBytes;
    private final int maxRequestsPerMinute;
    private final LongSupplier clockMillis;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public CauselineSecurityFilter(CauselineProperties properties, AccessToken token) {
        this(properties, token, System::currentTimeMillis);
    }

    CauselineSecurityFilter(CauselineProperties properties, AccessToken token, LongSupplier clockMillis) {
        this.token = token;
        this.allowedHosts = new HashSet<>(LOCAL_HOSTS);
        properties.allowedHosts().forEach(h -> allowedHosts.add(h.toLowerCase(Locale.ROOT)));
        this.allowedOrigins = Set.copyOf(properties.ingest().allowedOrigins());
        this.maxBytes = properties.ingest().maxBytes();
        this.maxRequestsPerMinute = properties.ingest().maxRequestsPerMinute();
        this.clockMillis = clockMillis;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CauselineWebConfiguration.isCauselinePath(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowedHosts.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "Host not allowed. Add it to causeline.allowed-hosts if this is intended.");
            return;
        }
        String path = path(request);
        if (path.equals(INGEST_PATH)) {
            String origin = request.getHeader("Origin");
            if (origin != null && !isSameOrigin(origin, request) && !isLocalToLocal(origin, request)
                    && !allowedOrigins.contains(origin)) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "Origin not allowed to send spans.");
                return;
            }
            long length = request.getContentLengthLong();
            if (length < 0) {
                reject(response, 411, "Content-Length required.");
                return;
            }
            if (length > maxBytes) {
                reject(response, 413, "Span upload too large.");
                return;
            }
            if (!tryAcquire(request.getRemoteAddr())) {
                reject(response, 429, "Too many span uploads.");
                return;
            }
        } else if (path.startsWith(API_PREFIX) && !token.matches(request.getHeader(TOKEN_HEADER))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Causeline access token missing or wrong. Open the link printed in the application log.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Fixed one-minute window per client address. */
    private boolean tryAcquire(String client) {
        long now = clockMillis.getAsLong();
        if (windows.size() > 10_000) {
            windows.clear(); // bound memory if something floods us from many addresses
        }
        long[] window = windows.compute(client, (k, w) -> {
            if (w == null || now - w[0] >= 60_000) {
                return new long[] {now, 1};
            }
            w[1]++;
            return w;
        });
        return window[1] <= maxRequestsPerMinute;
    }

    private static boolean isSameOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
            return request.getServerName().equalsIgnoreCase(uri.getHost()) && request.getServerPort() == port;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * A page on any localhost port posting to a localhost server. Dev proxies (Vite, CRA, Next)
     * rewrite the Host header, so a page on :5173 reaches :8080 looking cross-origin. A website on
     * the internet can never have a localhost origin, and ingest can only add spans.
     */
    private static boolean isLocalToLocal(String origin, HttpServletRequest request) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    && LOCAL_HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
