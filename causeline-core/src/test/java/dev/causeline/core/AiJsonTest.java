// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiJsonTest {

    @Test
    void readsWhatTheCaptureFiltersWrite() {
        assertThat(AiJson.objects(AiJson.parse(
                "[{\"id\":\"a\\\"b\",\"rank\":1,\"score\":1.0000000000000002},{\"text\":\"x\\r\\ny\\u00e9\",\"ok\":true,\"n\":null}]")))
                .hasSize(2)
                .first().isEqualTo(Map.of("id", "a\"b", "rank", 1.0, "score", 1.0000000000000002));
        assertThat(AiJson.parse(" [ ] ")).isEqualTo(List.of());
        assertThat(AiJson.parse("{}")).isEqualTo(Map.of());
        assertThat(AiJson.parse("[\"x\\r\\ny\\u00e9\"]")).isEqualTo(List.of("x\r\nyé"));
    }

    @Test
    void anythingElseIsNullNeverAnError() {
        for (String bad : List.of("[withheld on export]", "", "[", "{\"a\"", "{\"a\":1", "[1,", "\"open", "tru", "[1] x",
                "\"\\u12\"", "-", "[[[[[[[[[[[[1]]]]]]]]]]]]")) {
            assertThat(AiJson.parse(bad)).as(bad).isNull();
        }
        assertThat(AiJson.parse(null)).isNull();
        assertThat(AiJson.objects("text")).isEmpty();
        assertThat(AiJson.objects(null)).isEmpty();
    }
}
