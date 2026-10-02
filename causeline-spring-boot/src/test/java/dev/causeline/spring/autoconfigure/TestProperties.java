// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.autoconfigure;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Binds {@link CauselineProperties} the way Spring does, so tests get the real defaults. */
public final class TestProperties {

    private TestProperties() {
    }

    /** @param overrides relative to {@code causeline.}, e.g. {@code "capture.sql" -> "statement"} */
    public static CauselineProperties bind(Map<String, String> overrides) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        overrides.forEach((key, value) -> source.put("causeline." + key, value));
        return new Binder(source).bindOrCreate("causeline", CauselineProperties.class);
    }
}
