// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

/** Paths Causeline serves or ignores; shared by the servlet and the reactive web stacks. */
public final class CauselinePaths {

    public static final String BASE_PATH = "/causeline";
    static final String API_PREFIX = BASE_PATH + "/api/";
    static final String INGEST_PATH = BASE_PATH + "/api/spans";

    private CauselinePaths() {
    }

    public static boolean isCauselinePath(String uri) {
        return uri != null && (uri.equals(BASE_PATH) || uri.startsWith(BASE_PATH + "/"));
    }

    /**
     * Causeline's own requests, plus browser housekeeping requests (favicon, Chrome's .well-known
     * probes) that are never user actions.
     */
    public static boolean isIgnoredPath(String uri) {
        return isCauselinePath(uri)
                || "/favicon.ico".equals(uri)
                || (uri != null && uri.startsWith("/.well-known/"));
    }
}
