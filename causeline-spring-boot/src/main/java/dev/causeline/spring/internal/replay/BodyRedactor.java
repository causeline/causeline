// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import dev.causeline.spring.internal.capture.SensitiveData;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.StringJoiner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces sensitive values in request bodies with {@value #REDACTED} before they are kept for
 * replay. Handles JSON and form bodies; anything else is not kept at all.
 */
public final class BodyRedactor {

    public static final String REDACTED = SensitiveData.REDACTED;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SensitiveData sensitive;

    public BodyRedactor() {
        this(SensitiveData.defaults());
    }

    /** @param sensitive which keys to redact, including {@code causeline.capture.redact-keys} */
    public BodyRedactor(SensitiveData sensitive) {
        this.sensitive = sensitive;
    }

    /** @return the redacted body, or null when the content type is not supported or the body is malformed */
    public byte[] redact(String contentType, byte[] body) {
        if (contentType == null || body == null || body.length == 0) {
            return null;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        try {
            if (type.contains("json")) {
                JsonNode tree = JSON.readTree(body);
                redactNode(tree);
                return JSON.writeValueAsBytes(tree);
            }
            if (type.startsWith("application/x-www-form-urlencoded")) {
                return redactForm(new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            }
        } catch (RuntimeException e) {
            return null; // unparseable: better to keep nothing than something unredacted
        }
        return null;
    }

    private void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : object.propertyNames()) {
                if (sensitive.isSensitiveKey(name)) {
                    object.put(name, REDACTED);
                } else {
                    redactNode(object.get(name));
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(this::redactNode);
        }
    }

    private String redactForm(String form) {
        StringJoiner out = new StringJoiner("&");
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (eq >= 0 && sensitive.isSensitiveKey(key)) {
                out.add(pair.substring(0, eq) + "=" + URLEncoder.encode(REDACTED, StandardCharsets.UTF_8));
            } else {
                out.add(pair);
            }
        }
        return out.toString();
    }
}
