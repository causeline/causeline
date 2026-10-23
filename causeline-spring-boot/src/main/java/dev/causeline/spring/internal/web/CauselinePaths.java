// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

/** Paths Causeline serves or ignores; shared by the servlet and the reactive web stacks. */
public final class CauselinePaths {

    public static final String BASE_PATH = "/causeline";
    static final String API_PREFIX = BASE_PATH + "/api/";
    static final String INGEST_PATH = BASE_PATH + "/api/spans";
    /** springdoc's default location for the OpenAPI document. */
    static final String API_DOCS_PATH = "/v3/api-docs";

    private CauselinePaths() {
    }

    public static boolean isCauselinePath(String uri) {
        return uri != null && (uri.equals(BASE_PATH) || uri.startsWith(BASE_PATH + "/"));
    }

    /**
     * Causeline's own requests, plus requests that are never user actions: browser housekeeping
     * (favicon, Chrome's .well-known probes) and the OpenAPI document, which Causeline's contract
     * check and Swagger UI read.
     */
    public static boolean isIgnoredPath(String uri) {
        return isCauselinePath(uri)
                || "/favicon.ico".equals(uri)
                || (uri != null && (uri.equals(API_DOCS_PATH) || uri.startsWith(API_DOCS_PATH + "/")))
                || (uri != null && uri.startsWith("/.well-known/"));
    }
}
