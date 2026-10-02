// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.List;

/**
 * A trace saved to a file, e.g. to attach to a bug report and open in another developer's
 * Causeline. Contains exactly the spans Causeline stored, which are already redacted.
 *
 * @param format        always {@value #FORMAT}
 * @param schemaVersion the {@link Span#SCHEMA_VERSION} the spans were written with
 * @param exportedAt    ISO-8601 instant
 * @param exportedFrom  the application that recorded the trace
 */
public record TraceFile(String format, int schemaVersion, String exportedAt, String exportedFrom, List<Span> spans) {

    public static final String FORMAT = "causeline-trace";
    public static final int MAX_SPANS = 20_000;

    /**
     * Checks that the file can be imported as one trace.
     *
     * @return the trace ID shared by all spans
     * @throws IllegalArgumentException with a message fit to show the user
     */
    public String validate() {
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("Not a Causeline trace file (format is '" + format + "').");
        }
        if (schemaVersion != Span.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported trace file version " + schemaVersion
                    + "; this Causeline reads version " + Span.SCHEMA_VERSION + ".");
        }
        if (spans == null || spans.isEmpty()) {
            throw new IllegalArgumentException("The trace file contains no spans.");
        }
        if (spans.size() > MAX_SPANS) {
            throw new IllegalArgumentException("The trace file has " + spans.size() + " spans; the limit is " + MAX_SPANS + ".");
        }
        String traceId = spans.getFirst().traceId();
        if (spans.stream().anyMatch(s -> !s.traceId().equals(traceId))) {
            throw new IllegalArgumentException("A trace file must contain spans of one trace only.");
        }
        return traceId;
    }
}
