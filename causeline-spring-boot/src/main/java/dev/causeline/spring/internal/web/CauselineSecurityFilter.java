// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.LongSupplier;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies {@link CauselineGuard} to everything under {@code /causeline} in a servlet application. */
public final class CauselineSecurityFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = CauselineGuard.TOKEN_HEADER;

    private final CauselineGuard guard;

    public CauselineSecurityFilter(CauselineProperties properties, AccessToken token) {
        this(properties, token, System::currentTimeMillis);
    }

    CauselineSecurityFilter(CauselineProperties properties, AccessToken token, LongSupplier clockMillis) {
        this.guard = new CauselineGuard(properties, token, clockMillis);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CauselinePaths.isCauselinePath(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CauselineGuard.Rejection rejection = guard.check(new CauselineGuard.Request(request.getServerName(),
                request.getServerPort(), path(request), request.getHeader("Origin"), request.getContentLengthLong(),
                request.getRemoteAddr(), request.getHeader(TOKEN_HEADER)));
        if (rejection != null) {
            response.setStatus(rejection.status());
            response.setContentType("application/json");
            response.getWriter().write(rejection.json());
            return;
        }
        chain.doFilter(request, response);
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
