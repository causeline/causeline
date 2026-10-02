// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.util.unit.DataSize;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code causeline.*}. Every default is the most private and least exposed option.
 *
 * @param enabled                      turn Causeline on; off by default so it never runs by accident
 * @param accessToken                  token for the {@code /causeline} API; a random one is generated at startup when empty
 * @param iUnderstandThisIsProduction  allow Causeline under a production profile; strongly discouraged
 * @param allowedHosts                 extra host names that may serve {@code /causeline}, besides localhost
 * @param capture                      what data may be recorded
 * @param store                        how many traces are kept in memory
 * @param ingest                       limits for browser span uploads
 * @param replay                       where captured requests may be replayed
 * @param export                       optional forwarding of spans to other tools
 */
@ConfigurationProperties("causeline")
public record CauselineProperties(
        @DefaultValue("false") boolean enabled,
        String accessToken,
        @DefaultValue("false") boolean iUnderstandThisIsProduction,
        @DefaultValue List<String> allowedHosts,
        @DefaultValue Capture capture,
        @DefaultValue Store store,
        @DefaultValue Ingest ingest,
        @DefaultValue Replay replay,
        @DefaultValue Export export) {

    /**
     * @param otlp           forward spans to an OpenTelemetry (OTLP/HTTP) endpoint
     * @param upstream       send this service's spans to another application's Causeline, so a call
     *                       from service A to service B shows as one trace in A's UI
     * @param redactSecrets  redact credentials and sensitive values in everything that leaves this
     *                       application: OTLP export, the upstream Causeline and exported trace files.
     *                       The local UI still shows everything that was captured.
     */
    public record Export(@DefaultValue Otlp otlp, @DefaultValue Upstream upstream,
            @DefaultValue("true") boolean redactSecrets) {
    }

    /**
     * Another Causeline to send spans to: typically the service that calls this one.
     *
     * @param url     base URL of that application, e.g. {@code http://localhost:8080}; off when empty
     * @param token   that application's Causeline access token ({@code causeline.access-token} there)
     * @param timeout per-request timeout
     */
    public record Upstream(String url, String token, @DefaultValue("5s") Duration timeout) {

        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }

    /**
     * Spans are exported with secrets redacted unless {@code causeline.export.redact-secrets=false}.
     *
     * @param endpoint  OTLP/HTTP traces URL, e.g. {@code http://localhost:4318/v1/traces}; export is off when empty
     * @param headers   headers for the endpoint, e.g. an API key read from an environment variable
     * @param timeout   per-request timeout
     */
    public record Otlp(String endpoint, @DefaultValue Map<String, String> headers,
            @DefaultValue("10s") Duration timeout) {

        public boolean enabled() {
            return endpoint != null && !endpoint.isBlank();
        }
    }

    /**
     * @param targets                   replay targets by name. {@code local} (this application, no auth)
     *                                  always exists unless you define it yourself. Only these base URLs
     *                                  can ever be called.
     * @param sendOriginalCredentials   resend the original request's credential headers
     *                                  ({@code Authorization}, {@code Cookie}, ...). A target's auth
     *                                  profile still replaces {@code Authorization}.
     */
    public record Replay(@DefaultValue Map<String, Target> targets,
            @DefaultValue("true") boolean sendOriginalCredentials) {
    }

    /**
     * @param baseUrl         {@code self} for this application, otherwise e.g. {@code https://qa.example.internal}
     * @param auth            credentials for this target; replaces the original {@code Authorization} header
     * @param causelineToken  the target's Causeline access token, to fetch the replay's trace for comparison
     */
    public record Target(String baseUrl, @DefaultValue Auth auth, String causelineToken) {
    }

    /**
     * @param type     how to authenticate
     * @param token    for {@code bearer}
     * @param username for {@code basic}
     * @param password for {@code basic}
     * @param name     header name for {@code header}
     * @param value    header value for {@code header}
     */
    public record Auth(@DefaultValue("none") AuthType type, String token, String username, String password,
            String name, String value) {
    }

    public enum AuthType {
        NONE,
        BEARER,
        BASIC,
        HEADER,
        /** Headers come from the application's {@code ReplayAuthProvider} bean, e.g. a signed dev JWT. */
        PROVIDER
    }

    /**
     * Everything is captured and shown in the local UI by default, secrets included; each part can
     * be narrowed or turned off. What leaves the application is governed by
     * {@code causeline.export.redact-secrets}.
     *
     * @param requestBody       how request bodies are kept; see {@link RequestBodyCapture}
     * @param responseBody      whether response bodies are recorded (first 64 KB; the response itself is never delayed)
     * @param exceptionDetails  record exception messages and stack traces
     * @param headers           which request headers to record
     * @param query             which query values to record
     * @param pathValues        record the raw request path next to the route template
     * @param sql               how much of each SQL statement to record
     * @param arguments         record the arguments and return values of controller, service and
     *                          repository methods (shown on their spans; lazy JPA data is never loaded)
     * @param redactKeys        body, query and state keys whose values are replaced with [REDACTED] everywhere,
     *                          including the local UI (e.g. {@code password})
     */
    public record Capture(
            @DefaultValue("full") RequestBodyCapture requestBody,
            @DefaultValue("full") ResponseBodyCapture responseBody,
            @DefaultValue("true") boolean exceptionDetails,
            @DefaultValue Headers headers,
            @DefaultValue Query query,
            @DefaultValue("true") boolean pathValues,
            @DefaultValue("full") SqlCapture sql,
            @DefaultValue List<String> redactKeys,
            @DefaultValue("true") boolean arguments) {
    }

    /**
     * @param enabled record request headers at all
     * @param block   header names never to record or replay, e.g. {@code Cookie}
     */
    public record Headers(@DefaultValue("true") boolean enabled, @DefaultValue List<String> block) {
    }

    /**
     * @param enabled record query strings at all
     * @param block   query parameter names whose values show as [REDACTED]
     */
    public record Query(@DefaultValue("true") boolean enabled, @DefaultValue List<String> block) {
    }

    public enum SqlCapture {
        /** Operation and table only, e.g. {@code INSERT orders}. */
        OPERATION,
        /** The statement text with every literal replaced by {@code ?}. */
        STATEMENT,
        /** The statement text exactly as executed. Prepared-statement bind values are not available. */
        FULL
    }

    public enum ResponseBodyCapture {
        /** Response bodies are not recorded. */
        NONE,
        /** The first 64 KB of each response body is recorded and shown in the UI. */
        FULL
    }

    public enum RequestBodyCapture {
        /** Bodies are never stored. Only requests without a body can be replayed. */
        NONE,
        /** Bodies are stored in memory for replay only; never shown in the UI. */
        REPLAY_ONLY,
        /** Bodies are stored in memory, shown in the UI, and used for replay. */
        FULL
    }

    /**
     * Whichever limit is reached first applies; the least recently used trace is evicted first.
     *
     * @param maxTraces traces kept in memory
     * @param maxSize   estimated memory for stored spans
     * @param persist   keep traces across restarts in a file; secrets are redacted on disk unless
     *                  {@code causeline.export.redact-secrets=false}
     * @param directory where the file goes; defaults to {@code ~/.causeline/<spring.application.name>}
     */
    public record Store(@DefaultValue("1000") int maxTraces, @DefaultValue("64MB") DataSize maxSize,
            @DefaultValue("false") boolean persist, String directory) {
    }

    /**
     * @param allowedOrigins          origins besides the app's own that may upload browser spans
     * @param maxBytes                largest accepted upload
     * @param maxRequestsPerMinute    uploads accepted per client address per minute
     */
    public record Ingest(
            @DefaultValue List<String> allowedOrigins,
            @DefaultValue("262144") long maxBytes,
            @DefaultValue("300") int maxRequestsPerMinute) {
    }
}
