// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BodyRedactorTest {

    private final BodyRedactor redactor = new BodyRedactor();

    @Test
    void redactsSensitiveJsonKeysAtAnyDepth() {
        String body = """
                {"item":"book","quantity":2,"password":"hunter2",
                 "payment":{"cardNumber":"4111111111111111","cvv":"123","holder":"Alice"},
                 "tokens":[{"apiKey":"k-1"}],"lines":[{"sku":"A1"}]}
                """;

        String redacted = redact("application/json; charset=utf-8", body);

        assertThat(redacted).contains("\"item\":\"book\"", "\"quantity\":2", "\"holder\":\"Alice\"", "\"sku\":\"A1\"");
        assertThat(redacted).doesNotContain("hunter2", "4111111111111111", "123\"", "k-1");
        assertThat(redacted).contains("\"password\":\"[REDACTED]\"", "\"cardNumber\":\"[REDACTED]\"");
    }

    @Test
    void redactsSensitiveFormFields() {
        String redacted = redact("application/x-www-form-urlencoded", "user=alice&password=hunter2&remember=on");

        assertThat(redacted).isEqualTo("user=alice&password=%5BREDACTED%5D&remember=on");
    }

    @Test
    void keepsNothingForOtherTypesOrMalformedJson() {
        assertThat(redactor.redact("text/plain", "password=hunter2".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(redactor.redact("application/json", "{not json".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(redactor.redact(null, new byte[] {1})).isNull();
    }

    private String redact(String type, String body) {
        return new String(redactor.redact(type, body.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
