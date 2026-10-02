// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** How captured requests and bodies are kept and shown; shared by the servlet and the reactive capture. */
public final class CapturedBodies {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    static final int MAX_SHOWN_CHARS = 16 * 1024;

    static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    /**
     * Never kept for replay: set by the HTTP client or by Causeline itself for each new request.
     * Everything else, credentials included, is kept unless blocked.
     */
    static final Set<String> NOT_REPLAYED = Set.of("host", "content-length", "connection", "keep-alive",
            "transfer-encoding", "upgrade", "te", "trailer", "expect", "http2-settings",
            "traceparent", "tracestate", "baggage", "x-causeline-replay");

    private static final Pattern TEXT_TYPE =
            Pattern.compile("json|xml|text/|x-www-form-urlencoded|javascript|graphql", Pattern.CASE_INSENSITIVE);

    private CapturedBodies() {
    }

    static boolean isText(String contentType) {
        return contentType != null && TEXT_TYPE.matcher(contentType).find();
    }

    /** Hides only the user's redact-keys; bodies the redactor can't parse are kept as they are. */
    static byte[] hideBlockedKeys(BodyRedactor userRedactor, String contentType, byte[] body) {
        if (userRedactor == null) {
            return body;
        }
        byte[] redacted = userRedactor.redact(contentType, body);
        return redacted == null ? body : redacted;
    }

    /** A request body as the UI shows it: text as text, anything else by size and type. */
    static String shownRequest(String contentType, byte[] body) {
        String shown = isText(contentType)
                ? new String(body, StandardCharsets.UTF_8)
                : "[" + body.length + " bytes" + (contentType == null ? "" : ", " + contentType) + "]";
        return shown.length() <= MAX_SHOWN_CHARS
                ? shown
                : shown.substring(0, MAX_SHOWN_CHARS) + "… (" + shown.length() + " characters)";
    }

    /**
     * A response body as the UI shows it.
     *
     * @param copy  the first bytes the application wrote
     * @param total how many bytes it wrote in all
     */
    static String shownResponse(BodyRedactor userRedactor, String contentType, Charset charset, byte[] copy,
            long total) {
        boolean text = isText(contentType);
        byte[] body = text && total == copy.length ? hideBlockedKeys(userRedactor, contentType, copy) : copy;
        String shown = text
                ? new String(body, charset)
                : "[" + total + " bytes" + (contentType == null ? "" : ", " + contentType) + "]";
        if (total > copy.length && text) {
            shown += "… (first " + copy.length + " of " + total + " bytes)";
        }
        return shown;
    }
}
