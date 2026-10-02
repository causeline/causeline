// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import dev.causeline.spring.autoconfigure.CauselineProperties.Capture;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Adds {@link RequestDetails} to the server spans of a servlet application.
 * {@link ReactiveRequestCaptureFilter} does the same for WebFlux.
 */
public final class RequestCaptureFilter implements ObservationFilter {

    public static final String PREFIX = "causeline.capture.";
    public static final String QUERY = PREFIX + "url.query";
    public static final String PATH = PREFIX + "url.path";
    public static final String HEADER = PREFIX + "header.";
    public static final String BODY = PREFIX + "request.body";
    public static final String RESPONSE_BODY = PREFIX + "response.body";

    static final int MAX_VALUE_CHARS = RequestDetails.MAX_VALUE_CHARS;

    private final RequestDetails details;

    public RequestCaptureFilter(Capture capture, SensitiveData userBlocked) {
        this.details = new RequestDetails(capture, userBlocked);
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ServerRequestObservationContext server)) {
            return context;
        }
        HttpServletRequest request = server.getCarrier();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        details.addTo(context, headers, request.getQueryString(),
                request.getRequestURI().substring(request.getContextPath().length()));
        return context;
    }

    String blockQueryValues(String queryString) {
        return details.blockQueryValues(queryString);
    }

    static String truncate(String value) {
        return RequestDetails.truncate(value);
    }
}
