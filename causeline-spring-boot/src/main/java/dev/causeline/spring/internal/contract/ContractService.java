// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.contract;

import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.internal.contract.ContractChecker.Finding;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks one recorded response against the application's OpenAPI document (as springdoc serves it
 * at {@code /v3/api-docs}) and against the latest earlier run of the same request. Runs when
 * someone asks for it in the UI; the document is fetched then, over HTTP, and kept for a few seconds.
 */
public final class ContractService {

    /** A refusal worded for the person who clicked. */
    public static final class ContractException extends RuntimeException {
        ContractException(String message) {
            super(message);
        }
    }

    /** @param traceId the earlier run the response was compared with */
    public record Baseline(String traceId, long startTimeUnixNano) {
    }

    /**
     * @param spec         where the OpenAPI document was read from
     * @param specProblem  why there is no comparison with the document; null when there is one
     * @param findings     where the response and the document disagree
     * @param baseline     the earlier run, or null when this is the first with a JSON body
     * @param breaking     fields that run's response had and this one changed or dropped
     */
    public record Report(String spec, String specProblem, List<Finding> findings, Baseline baseline, List<Finding> breaking) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long SPEC_FRESH_NANOS = Duration.ofSeconds(10).toNanos();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TraceStore store;
    private final Supplier<String> specUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private JsonNode cachedSpec;
    private String cachedFrom;
    private long cachedAt;

    /** @param specUrl where the OpenAPI document is; asked on every check, since the port is known only after start-up */
    public ContractService(TraceStore store, Supplier<String> specUrl) {
        this.store = store;
        this.specUrl = specUrl;
    }

    public Report check(String traceId, String spanId) {
        List<Span> trace = store.get(traceId).orElseThrow(() -> new ContractException("That trace is no longer in the store."));
        Span request = trace.stream().filter(s -> s.spanId().equals(spanId)).findFirst()
                .orElseThrow(() -> new ContractException("That request is no longer in the trace."));
        String method = request.attributes().get("http.request.method");
        String status = request.attributes().get("http.response.status_code");
        if (request.kind() != SpanKind.REQUEST || method == null || status == null) {
            throw new ContractException("Only a server request with a recorded status can be checked.");
        }
        String route = request.attributes().getOrDefault("http.route", request.attributes().get("url.path"));
        if (route == null) {
            throw new ContractException("This request has no recorded path to look up in the OpenAPI document.");
        }
        JsonNode body = json(request.attributes().get("http.response.body"));

        String url = specUrl.get();
        List<Finding> findings = List.of();
        String problem = null;
        try {
            findings = ContractChecker.check(spec(url), method, route, Integer.parseInt(status), body);
        } catch (ContractException e) {
            problem = e.getMessage();
        }
        Optional<Span> earlier = body == null ? Optional.empty() : earlierRun(request, status);
        return new Report(url, problem, findings,
                earlier.map(s -> new Baseline(s.traceId(), s.startTimeUnixNano())).orElse(null),
                earlier.map(s -> ContractChecker.breaking(json(s.attributes().get("http.response.body")), body))
                        .orElse(List.of()));
    }

    /** The latest earlier run of the same request that answered with the same status and a JSON body. */
    private Optional<Span> earlierRun(Span request, String status) {
        return store.snapshot().stream()
                .flatMap(List::stream)
                .filter(s -> s.kind() == SpanKind.REQUEST && !s.traceId().equals(request.traceId())
                        && s.name().equals(request.name()) && s.source().equals(request.source())
                        && status.equals(s.attributes().get("http.response.status_code"))
                        && s.startTimeUnixNano() < request.startTimeUnixNano()
                        && json(s.attributes().get("http.response.body")) != null)
                .max(java.util.Comparator.comparingLong(Span::startTimeUnixNano));
    }

    private synchronized JsonNode spec(String url) {
        if (cachedSpec != null && url.equals(cachedFrom) && System.nanoTime() - cachedAt < SPEC_FRESH_NANOS) {
            return cachedSpec;
        }
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContractException("Reading the OpenAPI document at " + url + " was interrupted.");
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new ContractException("The OpenAPI document at " + url + " could not be read: " + e.getMessage());
        }
        if (response.statusCode() != 200) {
            throw new ContractException("No OpenAPI document at " + url + " (HTTP " + response.statusCode() + ")."
                    + " Add springdoc-openapi to the application, or set causeline.contract.spec-url.");
        }
        JsonNode spec = json(response.body());
        if (spec == null || !spec.path("paths").isObject()) {
            throw new ContractException(url + " did not return an OpenAPI document in JSON.");
        }
        cachedSpec = spec;
        cachedFrom = url;
        cachedAt = System.nanoTime();
        return spec;
    }

    /** The text as JSON; null when it is absent, not JSON, or a body recorded only as "[2048 bytes, image/png]". */
    private static JsonNode json(String text) {
        if (text == null || text.isBlank() || !(text.stripLeading().startsWith("{") || text.stripLeading().startsWith("["))) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            return null; // cut off at the capture limit, or not JSON after all
        }
    }
}
