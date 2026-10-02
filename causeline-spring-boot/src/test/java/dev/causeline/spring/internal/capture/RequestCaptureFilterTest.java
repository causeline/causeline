// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.autoconfigure.TestProperties;
import io.micrometer.common.KeyValue;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCaptureFilterTest {

    @Test
    void recordsEverythingByDefaultCredentialsIncluded() {
        MockHttpServletRequest request = request("/api/orders/42", "coupon=SAVE10&page=2");
        request.addHeader("Authorization", "Bearer user-token");
        request.addHeader("Cookie", "SESSION=abc");
        request.addHeader("X-Tenant-Id", "acme");

        Map<String, String> captured = capture(Map.of(), request);

        assertThat(captured)
                .containsEntry(RequestCaptureFilter.HEADER + "authorization", "Bearer user-token")
                .containsEntry(RequestCaptureFilter.HEADER + "cookie", "SESSION=abc")
                .containsEntry(RequestCaptureFilter.HEADER + "x-tenant-id", "acme")
                .containsEntry(RequestCaptureFilter.QUERY, "coupon=SAVE10&page=2")
                .containsEntry(RequestCaptureFilter.PATH, "/api/orders/42");
    }

    @Test
    void blockedHeadersAreNeverRecorded() {
        MockHttpServletRequest request = request("/api/orders", null);
        request.addHeader("Authorization", "Bearer user-token");
        request.addHeader("Cookie", "SESSION=abc");
        request.addHeader("X-Tenant-Id", "acme");

        Map<String, String> captured = capture(Map.of("capture.headers.block", "Authorization,cookie"), request);

        assertThat(captured).containsKey(RequestCaptureFilter.HEADER + "x-tenant-id")
                .doesNotContainKeys(RequestCaptureFilter.HEADER + "authorization", RequestCaptureFilter.HEADER + "cookie");
    }

    @Test
    void blockedAndRedactedQueryValuesAreHidden() {
        Map<String, String> captured = capture(
                Map.of("capture.query.block", "coupon", "capture.redact-keys", "token"),
                request("/api/orders", "page=2&coupon=SAVE10&access_token=abc"));

        assertThat(captured.get(RequestCaptureFilter.QUERY))
                .isEqualTo("page=2&coupon=[REDACTED]&access_token=[REDACTED]");
    }

    @Test
    void everyPartCanBeSwitchedOff() {
        MockHttpServletRequest request = request("/api/orders/42", "page=2");
        request.addHeader("Authorization", "Bearer user-token");

        Map<String, String> captured = capture(Map.of(
                "capture.headers.enabled", "false",
                "capture.query.enabled", "false",
                "capture.path-values", "false"), request);

        assertThat(captured).isEmpty();
    }

    private static Map<String, String> capture(Map<String, String> settings, MockHttpServletRequest request) {
        CauselineProperties properties = TestProperties.bind(settings);
        RequestCaptureFilter filter = new RequestCaptureFilter(properties.capture(),
                SensitiveData.userBlocked(properties.capture().redactKeys()));
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, new MockHttpServletResponse());
        filter.map(context);
        return StreamSupport.stream(context.getHighCardinalityKeyValues().spliterator(), false)
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    private static MockHttpServletRequest request(String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setQueryString(query);
        return request;
    }
}
