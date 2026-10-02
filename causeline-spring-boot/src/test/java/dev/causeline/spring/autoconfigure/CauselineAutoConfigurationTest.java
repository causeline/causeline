// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.autoconfigure.CauselineProperties.RequestBodyCapture;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class CauselineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withConfiguration(AutoConfigurations.of(CauselineAutoConfiguration.class));

    @Test
    void isOffByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CauselineProperties.class));
    }

    @Test
    void startsWhenEnabledCapturingEverythingButRedactingExports() {
        runner.withPropertyValues("causeline.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CauselineProperties.class);
            CauselineProperties properties = context.getBean(CauselineProperties.class);
            assertThat(properties.capture().requestBody()).isEqualTo(RequestBodyCapture.FULL);
            assertThat(properties.capture().exceptionDetails()).isTrue();
            assertThat(properties.capture().headers().enabled()).isTrue();
            assertThat(properties.capture().sql()).isEqualTo(CauselineProperties.SqlCapture.FULL);
            assertThat(properties.export().redactSecrets()).isTrue();
            assertThat(properties.replay().sendOriginalCredentials()).isTrue();
            assertThat(context).hasSingleBean(TraceStore.class).hasSingleBean(SpanProcessor.class);
        });
    }

    @Test
    void refusesToRunUnderProductionProfile() {
        runner.withPropertyValues("causeline.enabled=true", "spring.profiles.active=dev,production")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CauselineProperties.class));
    }

    @Test
    void productionWarningIsLoggedOncePerApplication(org.springframework.boot.test.system.CapturedOutput output) {
        // The condition guards several configuration classes, so Spring evaluates it several times.
        runner.withPropertyValues("causeline.enabled=true", "spring.profiles.active=prod",
                        "causeline.i-understand-this-is-production=true")
                .run(context -> assertThat(output.getOut().split("is running under production profile", -1))
                        .hasSize(2));
    }

    @Test
    void productionOverrideIsExplicit() {
        runner.withPropertyValues("causeline.enabled=true", "spring.profiles.active=prod",
                        "causeline.i-understand-this-is-production=true")
                .run(context -> assertThat(context).hasSingleBean(CauselineProperties.class));
    }

    @Test
    void recognisesProductionProfileNames() {
        assertThat(OnCauselineEnabledCondition.isProductionProfile("prod")).isTrue();
        assertThat(OnCauselineEnabledCondition.isProductionProfile("Production")).isTrue();
        assertThat(OnCauselineEnabledCondition.isProductionProfile("prod-eu")).isTrue();
        assertThat(OnCauselineEnabledCondition.isProductionProfile("dev")).isFalse();
        assertThat(OnCauselineEnabledCondition.isProductionProfile("qa")).isFalse();
        assertThat(OnCauselineEnabledCondition.isProductionProfile("product-catalog")).isFalse();
    }
}
