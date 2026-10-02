// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.reactive;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * A reactive checkout (WebFlux, R2DBC, WebClient) to show Causeline in a WebFlux application:
 * the same timeline as the servlet demo, with spans that last as long as the Monos they wrap.
 */
@SpringBootApplication
public class ReactiveCheckoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReactiveCheckoutApplication.class, args);
    }

    /** The fake payment provider stands in for a third-party service, so it is not traced itself. */
    @Bean
    ObservationPredicate ignoreFakePaymentProvider() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier() != null
                && server.getCarrier().getPath().value().startsWith("/fake-payment/"));
    }
}
