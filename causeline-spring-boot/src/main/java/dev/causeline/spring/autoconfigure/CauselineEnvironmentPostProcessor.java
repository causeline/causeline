// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.autoconfigure;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * When Causeline is enabled, adds low-priority defaults it needs from other libraries.
 * They sit last in the environment, so anything the application sets still wins.
 */
final class CauselineEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "causelineDefaults";

    private static final Map<String, Object> DEFAULTS = Map.of(
            // A debugging tool must see every request, not Boot's 10% sample.
            "management.tracing.sampling.probability", "1.0",
            // Registers Micrometer's ObservedAspect so @Observed services become spans.
            "management.observations.annotations.enabled", "true",
            // One span per SQL statement; connection and result-set spans are noise here.
            "jdbc.includes", "QUERY",
            // Messages sent and received become spans in the same trace (Spring Kafka, Spring AMQP).
            "spring.kafka.template.observation-enabled", "true",
            "spring.kafka.listener.observation-enabled", "true",
            "spring.rabbitmq.template.observation-enabled", "true",
            "spring.rabbitmq.listener.simple.observation-enabled", "true",
            "spring.rabbitmq.listener.direct.observation-enabled", "true",
            // WebFlux: the current span follows the request through Reactor operators, so services,
            // queries and calls made inside a Mono or Flux stay in the request's trace.
            "spring.reactor.context-propagation", "auto");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("causeline.enabled", Boolean.class, false)) {
            return;
        }
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(OnCauselineEnabledCondition::isProductionProfile);
        if (production && !environment.getProperty("causeline.i-understand-this-is-production", Boolean.class, false)) {
            return;
        }
        Map<String, Object> defaults = new HashMap<>(DEFAULTS);
        // With full SQL capture (the default) the bound values are recorded too, so a statement
        // can be shown as it actually ran.
        String sql = environment.getProperty("causeline.capture.sql", "full").trim();
        if (sql.equalsIgnoreCase("full")) {
            defaults.put("jdbc.datasource-proxy.include-parameter-values", "true");
            defaults.put("management.observations.r2dbc.include-parameter-values", "true");
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        // After config data has been loaded, so profiles and causeline.* are known.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
