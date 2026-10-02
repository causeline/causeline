// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class CauselineEnvironmentPostProcessorTest {

    private final CauselineEnvironmentPostProcessor processor = new CauselineEnvironmentPostProcessor();

    @Test
    void addsNothingWhenDisabled() {
        StandardEnvironment env = environment(Map.of());

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.tracing.sampling.probability")).isNull();
    }

    @Test
    void addsDefaultsWhenEnabledWithoutOverridingTheApplication() {
        StandardEnvironment env = environment(Map.of("causeline.enabled", "true",
                "management.tracing.sampling.probability", "0.5"));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.5");
        assertThat(env.getProperty("management.observations.annotations.enabled")).isEqualTo("true");
        assertThat(env.getProperty("jdbc.includes")).isEqualTo("QUERY");
    }

    @Test
    void recordsSqlParameterValuesOnlyWithFullSqlCapture() {
        StandardEnvironment full = environment(Map.of("causeline.enabled", "true"));
        StandardEnvironment statement = environment(Map.of("causeline.enabled", "true", "causeline.capture.sql", "statement"));

        processor.postProcessEnvironment(full, new SpringApplication());
        processor.postProcessEnvironment(statement, new SpringApplication());

        assertThat(full.getProperty("jdbc.datasource-proxy.include-parameter-values")).isEqualTo("true");
        assertThat(statement.getProperty("jdbc.datasource-proxy.include-parameter-values")).isNull();
    }

    @Test
    void addsNothingUnderProductionProfile() {
        StandardEnvironment env = environment(Map.of("causeline.enabled", "true"));
        env.setActiveProfiles("production");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("jdbc.includes")).isNull();
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return env;
    }
}
