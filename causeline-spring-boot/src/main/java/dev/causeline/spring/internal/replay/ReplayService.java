// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.replay;

import dev.causeline.core.TraceStore;
import dev.causeline.spring.ReplayAuthProvider;
import dev.causeline.spring.autoconfigure.CauselineProperties;
import dev.causeline.spring.autoconfigure.CauselineProperties.Auth;
import dev.causeline.spring.autoconfigure.CauselineProperties.AuthType;
import dev.causeline.spring.autoconfigure.CauselineProperties.Target;
import dev.causeline.spring.internal.capture.SensitiveData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Sends a captured request again, to a configured target only (PRD section 8):
 * <ol>
 *   <li>rebuilds it from its {@link ReplayRecord}: method, path, query, headers and body;</li>
 *   <li>resends the original's credential headers unless
 *       {@code causeline.replay.send-original-credentials=false}; the target's auth profile, when
 *       set, replaces {@code Authorization};</li>
 *   <li>starts a new trace, marks the request with {@value #REPLAY_HEADER}, and replaces any
 *       {@code Idempotency-Key} so the server treats it as a new request;</li>
 *   <li>refuses unsafe methods unless the caller confirmed the side effects.</li>
 * </ol>
 */
public final class ReplayService {

    public static final String REPLAY_HEADER = "X-Causeline-Replay";
    public static final String LOCAL_TARGET = "local";
    static final String SELF = "self";
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Headers java.net.http refuses to let callers set. */
    private static final java.util.Set<String> RESTRICTED_BY_CLIENT =
            java.util.Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final ReplayStore records;
    private final TraceStore traces;
    private final Map<String, Target> targets;
    private final boolean sendOriginalCredentials;
    private final Optional<ReplayAuthProvider> authProvider;
    private final IntSupplier localPort;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public ReplayService(ReplayStore records, TraceStore traces, CauselineProperties.Replay config,
            Optional<ReplayAuthProvider> authProvider, IntSupplier localPort) {
        this.records = records;
        this.traces = traces;
        this.targets = withLocal(config.targets());
        this.sendOriginalCredentials = config.sendOriginalCredentials();
        this.authProvider = authProvider;
        this.localPort = localPort;
        // Redirects are not followed: a replay must only ever reach the configured base URL.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static Map<String, Target> withLocal(Map<String, Target> configured) {
        Map<String, Target> all = new LinkedHashMap<>();
        all.put(LOCAL_TARGET, new Target(SELF, new Auth(AuthType.NONE, null, null, null, null, null), null));
        all.putAll(configured);
        return Map.copyOf(all);
    }

    public Map<String, Target> targets() {
        return targets;
    }

    public boolean sendsOriginalCredentials() {
        return sendOriginalCredentials;
    }

    public boolean isLocal(String targetName) {
        Target target = targets.get(targetName);
        return target != null && SELF.equals(target.baseUrl());
    }

    public Optional<ReplayRecord> record(String traceId, String spanId) {
        return records.get(traceId, spanId);
    }

    /**
     * @param bodyOverride a body typed by the developer, used when none was captured; may be null
     */
    public Outcome replay(String traceId, String spanId, String targetName, boolean confirmed, String bodyOverride) {
        ReplayRecord record = records.get(traceId, spanId).orElseThrow(() -> new ReplayException(404,
                "Nothing captured to replay this request. It may be older than the replay buffer."));
        Target target = targets.get(targetName);
        if (target == null) {
            throw new ReplayException(400, "Unknown replay target '" + targetName + "'. Configure it under causeline.replay.targets.");
        }
        if (record.isUnsafe() && !confirmed) {
            throw new ReplayException(409, "Replaying " + record.method() + " " + record.path() + " on " + targetName
                    + " repeats its side effects. Confirm to continue.");
        }
        if (record.queryMissing()) {
            throw new ReplayException(422, "The original query string was not captured. "
                    + "Set causeline.capture.request-body=replay-only to replay requests with query parameters.");
        }
        byte[] body = bodyOverride != null ? bodyOverride.getBytes(StandardCharsets.UTF_8) : record.body();
        if (record.bodyMissing() && body == null) {
            throw new ReplayException(422, "The original body was not captured. Paste a body to replay, "
                    + "or set causeline.capture.request-body=replay-only.");
        }

        String replayTraceId = randomHex(16);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(target, record))
                .timeout(TIMEOUT)
                .method(record.method(), body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        Map<String, String> auth = authHeaders(targetName, target.auth());
        record.headers().forEach((name, value) -> {
            boolean credential = SensitiveData.isCredentialHeader(name);
            boolean replacedByTarget = auth.keySet().stream().anyMatch(name::equalsIgnoreCase);
            boolean replacedHere = name.equalsIgnoreCase("Idempotency-Key");
            if ((sendOriginalCredentials || !credential) && !replacedByTarget && !replacedHere
                    && !RESTRICTED_BY_CLIENT.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                request.header(name, value);
            }
        });
        // The target's own credentials win over the original request's.
        auth.forEach(request::setHeader);
        request.setHeader("traceparent", "00-" + replayTraceId + "-" + randomHex(8) + "-01");
        request.setHeader(REPLAY_HEADER, traceId);
        if (record.hadIdempotencyKey()) {
            request.setHeader("Idempotency-Key", UUID.randomUUID().toString());
        }

        traces.pin(traceId);
        long start = System.nanoTime();
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            boolean sentRedacted = bodyOverride == null && record.body() != null
                    && new String(record.body(), StandardCharsets.UTF_8).contains(BodyRedactor.REDACTED);
            return new Outcome(replayTraceId, targetName, response.statusCode(), System.nanoTime() - start, sentRedacted);
        } catch (IOException e) {
            throw new ReplayException(502, "Could not reach " + targetName + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReplayException(502, "Replay interrupted");
        }
    }

    /** Reads a trace recorded by a remote target's Causeline, as raw JSON. */
    public Optional<String> fetchRemoteTrace(String targetName, String traceId) {
        Target target = targets.get(targetName);
        if (target == null || target.causelineToken() == null) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(target.baseUrl()) + "/causeline/api/traces/" + traceId))
                .timeout(Duration.ofSeconds(10))
                .header("X-Causeline-Token", target.causelineToken())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private URI uri(Target target, ReplayRecord record) {
        String base = SELF.equals(target.baseUrl()) ? "http://localhost:" + localPort.getAsInt() : stripSlash(target.baseUrl());
        return URI.create(base + record.path() + (record.query() == null ? "" : "?" + record.query()));
    }

    private Map<String, String> authHeaders(String targetName, Auth auth) {
        return switch (auth.type()) {
            case NONE -> Map.of();
            case BEARER -> Map.of("Authorization", "Bearer " + required(auth.token(), targetName, "token"));
            case BASIC -> Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (required(auth.username(), targetName, "username") + ":" + required(auth.password(), targetName, "password"))
                            .getBytes(StandardCharsets.UTF_8)));
            case HEADER -> Map.of(required(auth.name(), targetName, "name"), required(auth.value(), targetName, "value"));
            case PROVIDER -> authProvider.orElseThrow(() -> new ReplayException(500,
                    "Target '" + targetName + "' uses auth type provider, but no ReplayAuthProvider bean exists."))
                    .headers(targetName);
        };
    }

    private static String required(String value, String target, String field) {
        if (value == null || value.isBlank()) {
            throw new ReplayException(500, "Replay target '" + target + "' is missing auth." + field + ".");
        }
        return value;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        do {
            random.nextBytes(buffer);
        } while (HexFormat.of().formatHex(buffer).matches("0+"));
        return HexFormat.of().formatHex(buffer);
    }

    /**
     * @param sentRedactedFields the captured body had sensitive fields, which were sent as
     *                           "[REDACTED]" and may change the outcome
     */
    public record Outcome(String replayTraceId, String target, int httpStatus, long durationNanos,
            boolean sentRedactedFields) {
    }

    /** A replay that cannot be performed, with the HTTP status the API should answer with. */
    public static final class ReplayException extends RuntimeException {
        private final int status;

        public ReplayException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
