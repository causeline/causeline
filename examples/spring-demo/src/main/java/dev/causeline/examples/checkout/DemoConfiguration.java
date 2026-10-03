// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableCaching
class DemoConfiguration {

    /**
     * The fake payment provider stands in for a third-party service, which would not report spans,
     * and the demo's settings endpoint is plumbing. Neither should appear in traces.
     */
    @Bean
    ObservationPredicate hideDemoPlumbing() {
        return (name, context) -> {
            String uri = context instanceof ServerRequestObservationContext request
                    ? request.getCarrier().getRequestURI()
                    : currentRequestUri();
            return uri == null || !(uri.startsWith("/fake-payment/") || uri.startsWith("/api/demo/"));
        };
    }

    private static String currentRequestUri() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRequestURI()
                : null;
    }
}
