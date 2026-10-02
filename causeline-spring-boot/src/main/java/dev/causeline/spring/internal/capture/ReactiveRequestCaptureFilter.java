// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import dev.causeline.spring.autoconfigure.CauselineProperties.Capture;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/** Adds {@link RequestDetails} to the server spans of a WebFlux application. */
public final class ReactiveRequestCaptureFilter implements ObservationFilter {

    private final RequestDetails details;

    public ReactiveRequestCaptureFilter(Capture capture, SensitiveData userBlocked) {
        this.details = new RequestDetails(capture, userBlocked);
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ServerRequestObservationContext server) || server.getCarrier() == null) {
            return context;
        }
        ServerHttpRequest request = server.getCarrier();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.getHeaders().forEach(headers::put);
        details.addTo(context, headers, request.getURI().getRawQuery(),
                request.getPath().pathWithinApplication().value());
        return context;
    }
}
