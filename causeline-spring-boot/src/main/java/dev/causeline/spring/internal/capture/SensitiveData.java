// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Which keys and headers count as sensitive. Two uses:
 * <ul>
 *   <li>{@link #userBlocked(List)}: only the keys the user listed in {@code causeline.capture.redact-keys};
 *       applied when capturing, so they are hidden even in the local UI;</li>
 *   <li>{@link #forExport(List)}: the built-in list of credentials plus the user's keys; applied to
 *       everything that leaves the application (OTLP, trace files).</li>
 * </ul>
 */
public final class SensitiveData {

    public static final String REDACTED = "[REDACTED]";

    private static final String BUILT_IN_KEYS =
            "password|passwd|secret|token|authorization|api[-_]?key|card|cvv|cvc|ssn|iban|pin";

    private static final Set<String> CREDENTIAL_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key");
    private static final Pattern CREDENTIAL_HEADER_WORDS =
            Pattern.compile("token|secret|password|session|auth", Pattern.CASE_INSENSITIVE);

    private final Pattern sensitiveKey;

    private SensitiveData(boolean builtIns, List<String> extraKeys) {
        String extra = extraKeys.stream()
                .filter(k -> !k.isBlank())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        String pattern = builtIns ? (extra.isEmpty() ? BUILT_IN_KEYS : BUILT_IN_KEYS + "|" + extra) : extra;
        this.sensitiveKey = pattern.isEmpty() ? null : Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    }

    /** Only the user's {@code redact-keys}: what to hide at capture time. Empty by default. */
    public static SensitiveData userBlocked(List<String> redactKeys) {
        return new SensitiveData(false, redactKeys);
    }

    /** Built-in credentials plus the user's keys: what to hide in anything exported. */
    public static SensitiveData forExport(List<String> redactKeys) {
        return new SensitiveData(true, redactKeys);
    }

    public static SensitiveData defaults() {
        return forExport(List.of());
    }

    public boolean isEmpty() {
        return sensitiveKey == null;
    }

    /** A body, query or state key whose value must be hidden. */
    public boolean isSensitiveKey(String key) {
        return sensitiveKey != null && sensitiveKey.matcher(key).find();
    }

    private static final Pattern KEY_VALUE = Pattern.compile(
            "([\"']?)([\\w.\\-]+)\\1(\\s*[=:]\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s,;&}\\]]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[\\w\\-._~+/]+=*");

    /**
     * Free text (a log line, an exception message) with the values of sensitive {@code key=value},
     * {@code key: value} and {@code "key":"value"} pairs, and bearer or basic credentials, hidden.
     */
    public String scrubText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutCredentials = BEARER.matcher(text).replaceAll("$1 " + REDACTED);
        if (sensitiveKey == null) {
            return withoutCredentials;
        }
        // A value can itself start with a pair ("user: password=x"), so after a harmless key the
        // search resumes right behind its separator instead of skipping the whole value.
        java.util.regex.Matcher m = KEY_VALUE.matcher(withoutCredentials);
        StringBuilder out = new StringBuilder(withoutCredentials.length());
        int copied = 0;
        int from = 0;
        while (from < withoutCredentials.length() && m.find(from)) {
            if (isSensitiveKey(m.group(2))) {
                out.append(withoutCredentials, copied, m.start(4)).append(REDACTED);
                copied = m.end(4);
                from = m.end(4);
            } else {
                from = m.end(3);
            }
        }
        return out.append(withoutCredentials.substring(copied)).toString();
    }

    /** A header that carries credentials. */
    public static boolean isCredentialHeader(String name) {
        return CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT)) || CREDENTIAL_HEADER_WORDS.matcher(name).find();
    }
}
