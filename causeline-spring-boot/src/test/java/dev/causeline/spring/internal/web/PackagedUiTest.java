// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The starter jar must carry the UI; Maven builds it in generate-resources. */
class PackagedUiTest {

    @Test
    void uiIsOnTheClasspathAndServedUnderCauseline() throws IOException {
        try (InputStream index = getClass().getResourceAsStream("/causeline-ui/index.html")) {
            assertThat(index).as("causeline-ui/index.html; run `npm install` once, then build with Maven").isNotNull();
            String html = new String(index.readAllBytes(), StandardCharsets.UTF_8);
            // Asset URLs must resolve under the embedded path.
            assertThat(html).contains("/causeline/assets/");
        }
    }
}
