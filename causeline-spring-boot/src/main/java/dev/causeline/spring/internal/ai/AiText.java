// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.ai;

import dev.causeline.spring.internal.capture.SensitiveData;

/** Caps text and writes the small JSON values AI spans carry, with no JSON library on the classpath. */
final class AiText {

    static final int MAX_TEXT_CHARS = 65_536;
    static final int MAX_DOCUMENT_CHARS = 4_096;
    static final int MAX_DOCUMENTS = 50;
    static final int MAX_ID_CHARS = 200;

    private final SensitiveData userBlocked;

    AiText(SensitiveData userBlocked) {
        this.userBlocked = userBlocked;
    }

    /**
     * Applies {@code capture.redact-keys}, which hide values even locally, then the cap. Only called
     * when keys are configured to match, since it runs on the application's thread.
     */
    String clean(String text, int maxChars) {
        String capped = cap(text, maxChars);
        return userBlocked.isEmpty() ? capped : userBlocked.scrubText(capped);
    }

    static String cap(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    static void string(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
