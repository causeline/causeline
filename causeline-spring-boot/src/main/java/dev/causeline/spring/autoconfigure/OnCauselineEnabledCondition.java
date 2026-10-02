// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.autoconfigure;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code causeline.enabled=true} and no production profile is active.
 * Under a production profile Causeline switches itself off and logs an error rather than
 * failing the application, unless {@code causeline.i-understand-this-is-production=true}.
 */
final class OnCauselineEnabledCondition extends SpringBootCondition {

    private static final Logger log = LoggerFactory.getLogger(OnCauselineEnabledCondition.class);

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        if (!env.getProperty("causeline.enabled", Boolean.class, false)) {
            return ConditionOutcome.noMatch("causeline.enabled is not true");
        }
        String productionProfile = Arrays.stream(env.getActiveProfiles())
                .filter(OnCauselineEnabledCondition::isProductionProfile)
                .findFirst()
                .orElse(null);
        if (productionProfile == null) {
            return ConditionOutcome.match("causeline.enabled is true and no production profile is active");
        }
        if (env.getProperty("causeline.i-understand-this-is-production", Boolean.class, false)) {
            if (firstTimeFor(env)) {
                log.warn("Causeline is running under production profile '{}' because "
                        + "causeline.i-understand-this-is-production=true. Causeline is a development tool.",
                        productionProfile);
            }
            return ConditionOutcome.match("production override set");
        }
        if (firstTimeFor(env)) {
            log.error("Causeline is disabled: it refuses to run under production profile '{}'. "
                    + "Remove causeline.enabled from production configuration.", productionProfile);
        }
        return ConditionOutcome.noMatch("production profile '" + productionProfile + "' is active");
    }

    // Spring evaluates the condition once per configuration class it guards; say it once per application.
    private static final Map<Environment, Boolean> WARNED = Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean firstTimeFor(Environment env) {
        return WARNED.put(env, Boolean.TRUE) == null;
    }

    // "prod", "production", and variants like "prod-eu"; not unrelated names such as "product-catalog".
    private static final Pattern PRODUCTION_PROFILE = Pattern.compile("prod(uction)?([-_.].*)?");

    static boolean isProductionProfile(String profile) {
        return PRODUCTION_PROFILE.matcher(profile.toLowerCase(Locale.ROOT)).matches();
    }
}
