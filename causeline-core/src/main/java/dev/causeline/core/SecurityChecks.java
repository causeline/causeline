// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import dev.causeline.core.Insight.Rule;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Security rules on captured traffic (PRD section 7), labelled with the OWASP Top 10:2025
 * category. They only read the spans of one trace; nothing is sent anywhere.
 *
 * <p>Labels name requests, fields, headers and cookies, never the values captured for them, so a
 * finding can be shown or exported without leaking what it is about.
 */
final class SecurityChecks {

    static final String REQUEST_HEADER = "http.request.header.";
    static final String RESPONSE_HEADER = "http.response.header.";

    /** Shorter values match too many SQL literals by chance ('EUR', 'PAID'). */
    static final int MIN_STRING_VALUE = 3;
    /** Small numbers are everywhere in SQL (LIMIT 1, quantity = 2). */
    static final int MIN_NUMBER_DIGITS = 3;

    private static final Pattern SENSITIVE_WORD = Pattern.compile(
            "(^|_)(password|passwd|pwd|secret|token|access_token|refresh_token|id_token|api_key|apikey|ssn|cvv|cvc"
                    + "|private_key|client_secret)($|_)");
    private static final Pattern SESSION_COOKIE = Pattern.compile("(?i)sess|sid|token|auth|jwt|remember");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("(?<![\\w.$])-?\\d+(?:\\.\\d+)?(?![\\w.])");
    // The value is matched with a plain character class, which the regex engine runs iteratively:
    // an alternation like (?:[^"\]|\.)* recurses per character and overflows the stack on a long
    // value (e.g. a stack trace in a response body). A value with an escaped quote is cut short,
    // which is fine for detection.
    private static final Pattern JSON_FIELD = Pattern.compile(
            "\"([^\"\\\\]{1,100})\"\\s*:\\s*(?:\"([^\"]{0,4096})\"|(-?\\d+(?:\\.\\d+)?))");
    /** Bodies and messages are scanned only up to here, so a huge one can't make a regex run away. */
    private static final int MAX_SCAN_CHARS = 32_768;
    private static final Pattern STACK_FRAME = Pattern.compile(
            "at [A-Za-z_$][\\w$]*(?:\\.[\\w$<>]+)+\\([\\w$]+\\.(?:java|kt):\\d+\\)");
    private static final Pattern SQL_ERROR = Pattern.compile(
            "SQLException|SQLSyntaxErrorException|BadSqlGrammarException|syntax error at or near"
                    + "|You have an error in your SQL syntax|ORA-\\d{5}|org\\.h2\\.jdbc");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+");

    private SecurityChecks() {
    }

    static void analyze(List<TraceView.Row> rows, List<Insight> insights) {
        Map<String, TraceView.Row> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.spanId(), r));
        List<TraceView.Row> serverRequests = rows.stream()
                .filter(r -> r.kind() == SpanKind.REQUEST && !"browser".equals(r.source()))
                .toList();
        requestValuesInSql(rows, serverRequests, byId, insights);
        missingSecurityHeaders(serverRequests, insights);
        serverVersionDisclosed(serverRequests, insights);
        permissiveCors(serverRequests, insights);
        sessionCookies(serverRequests, insights);
        secretsInUrls(rows, insights);
        weakJwts(serverRequests, insights);
        errorDetails(serverRequests, insights);
        secretsInLogs(rows, serverRequests, byId, insights);
        sensitiveFieldsInResponse(serverRequests, insights);
        requestValuesSteeringOutboundCalls(rows, serverRequests, byId, insights);
    }

    // A05 Injection ---------------------------------------------------------------------------

    /**
     * A value the client sent that shows up as a literal in the prepared SQL of the same request:
     * the code put it into the SQL text instead of binding it. Bound values never appear in the
     * prepared statement, which still has its {@code ?} placeholders.
     */
    private static void requestValuesInSql(List<TraceView.Row> rows, List<TraceView.Row> serverRequests,
            Map<String, TraceView.Row> byId, List<Insight> insights) {
        Set<String> reported = new LinkedHashSet<>();
        for (TraceView.Row request : serverRequests) {
            Map<String, String> inputs = requestInputs(request);
            if (inputs.isEmpty()) {
                continue;
            }
            for (TraceView.Row query : rows) {
                if (query.kind() != SpanKind.DATABASE || !isUnder(query, request, byId)) {
                    continue;
                }
                String prepared = preparedSql(query);
                if (prepared == null) {
                    continue;
                }
                Set<String> literals = literals(prepared);
                List<String> fields = inputs.entrySet().stream()
                        .filter(input -> literals.contains(input.getValue()))
                        .map(Map.Entry::getKey)
                        .distinct()
                        .toList();
                if (fields.isEmpty() || !reported.add(query.name() + "|" + String.join(",", fields))) {
                    continue;
                }
                insights.add(new Insight(Rule.REQUEST_VALUE_IN_SQL, query.spanId(),
                        "Request value written into SQL (OWASP A05 Injection): " + String.join(", ", fields)
                                + " from " + request.name() + " appears in the text of " + query.name()
                                + " instead of being bound. Pass it as a parameter (?, :name, a repository method"
                                + " argument), never by building the SQL string",
                        0));
            }
        }
    }

    /** The statement as the application prepared it, or null when Causeline only kept a sanitized form. */
    private static String preparedSql(TraceView.Row query) {
        String statement = query.attributes().get("db.query.statement");
        if (statement != null) {
            return statement;
        }
        // Without bind values the full text is the statement exactly as prepared.
        boolean bound = query.attributes().containsKey("db.query.parameters");
        String text = query.attributes().get("db.query.text");
        return bound || text == null ? null : text;
    }

    /** Field name → value for everything the client sent in the query, path variables and body. */
    static Map<String, String> requestInputs(TraceView.Row request) {
        Map<String, String> inputs = new LinkedHashMap<>();
        String query = request.attributes().get("url.query");
        if (query != null) {
            formPairs(query, "query ", inputs);
        }
        pathVariables(request.attributes().get("http.route"), request.attributes().get("url.path"), inputs);
        String body = cap(request.attributes().get("http.request.body"));
        if (body != null) {
            String trimmed = body.stripLeading();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                Matcher field = JSON_FIELD.matcher(body);
                while (field.find()) {
                    String value = field.group(2) != null ? unescapeJson(field.group(2)) : field.group(3);
                    putInput(inputs, "body field " + field.group(1), value);
                }
            } else if (body.contains("=")) {
                formPairs(body, "form field ", inputs);
            }
        }
        return inputs;
    }

    private static void formPairs(String encoded, String prefix, Map<String, String> inputs) {
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                putInput(inputs, prefix + decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
    }

    private static void pathVariables(String route, String path, Map<String, String> inputs) {
        if (route == null || path == null) {
            return;
        }
        String[] template = route.split("/");
        String[] actual = path.split("/");
        if (template.length != actual.length) {
            return;
        }
        for (int i = 0; i < template.length; i++) {
            if (template[i].startsWith("{") && template[i].endsWith("}")) {
                putInput(inputs, "path variable " + template[i], decode(actual[i]));
            }
        }
    }

    private static void putInput(Map<String, String> inputs, String field, String value) {
        if (value == null || value.equals("[REDACTED]")) {
            return;
        }
        boolean number = value.matches("-?\\d+(\\.\\d+)?");
        long digits = value.chars().filter(Character::isDigit).count();
        if (number ? digits >= MIN_NUMBER_DIGITS : value.length() >= MIN_STRING_VALUE) {
            inputs.putIfAbsent(field, value);
        }
    }

    /** String literals ('it''s' → it's) and numbers outside them. */
    static Set<String> literals(String sql) {
        Set<String> literals = new LinkedHashSet<>();
        StringBuilder outside = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c != '\'') {
                outside.append(c);
                i++;
                continue;
            }
            StringBuilder literal = new StringBuilder();
            i++;
            while (i < sql.length()) {
                if (sql.charAt(i) == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        literal.append('\'');
                        i += 2;
                        continue;
                    }
                    break;
                }
                literal.append(sql.charAt(i++));
            }
            i++;
            literals.add(literal.toString());
            outside.append(' ');
        }
        Matcher number = NUMBER_LITERAL.matcher(outside);
        while (number.find()) {
            literals.add(number.group());
        }
        return literals;
    }

    // A02 Security Misconfiguration ------------------------------------------------------------

    /** One finding per trace: the same missing headers on every endpoint would only repeat it. */
    private static void missingSecurityHeaders(List<TraceView.Row> serverRequests, List<Insight> insights) {
        for (TraceView.Row request : serverRequests) {
            Map<String, String> headers = responseHeaders(request);
            String contentType = headers.get("content-type");
            if (contentType == null) {
                continue; // headers not captured, or no body to protect
            }
            List<String> missing = new ArrayList<>();
            if (!headers.getOrDefault("x-content-type-options", "").toLowerCase(Locale.ROOT).contains("nosniff")) {
                missing.add("X-Content-Type-Options: nosniff");
            }
            if (contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
                String csp = headers.get("content-security-policy");
                if (csp == null) {
                    missing.add("Content-Security-Policy");
                }
                boolean framing = headers.containsKey("x-frame-options")
                        || (csp != null && csp.toLowerCase(Locale.ROOT).contains("frame-ancestors"));
                if (!framing) {
                    missing.add("X-Frame-Options (or CSP frame-ancestors)");
                }
            }
            if (!missing.isEmpty()) {
                insights.add(new Insight(Rule.MISSING_SECURITY_HEADERS, request.spanId(),
                        "Missing security headers (OWASP A02 Security Misconfiguration): " + request.name()
                                + " was answered without " + String.join(", ", missing)
                                + ". Spring Security adds them by default; without it, set them in a filter",
                        0));
                return;
            }
        }
    }

    private static void serverVersionDisclosed(List<TraceView.Row> serverRequests, List<Insight> insights) {
        for (TraceView.Row request : serverRequests) {
            Map<String, String> headers = responseHeaders(request);
            List<String> disclosed = new ArrayList<>();
            if (headers.containsKey("x-powered-by")) {
                disclosed.add("X-Powered-By");
            }
            if (VERSION.matcher(headers.getOrDefault("server", "")).find()) {
                disclosed.add("Server");
            }
            if (!disclosed.isEmpty()) {
                insights.add(new Insight(Rule.SERVER_VERSION_DISCLOSED, request.spanId(),
                        "Server details disclosed (OWASP A02 Security Misconfiguration): " + request.name()
                                + " sent " + String.join(" and ", disclosed)
                                + ", which tells an attacker which versions to look up. Remove the header",
                        0));
                return;
            }
        }
    }

    private static void permissiveCors(List<TraceView.Row> serverRequests, List<Insight> insights) {
        for (TraceView.Row request : serverRequests) {
            Map<String, String> headers = responseHeaders(request);
            String origin = headers.getOrDefault("access-control-allow-origin", "").trim();
            boolean credentials = "true".equalsIgnoreCase(headers.getOrDefault("access-control-allow-credentials", "").trim());
            if (credentials && (origin.equals("*") || origin.equalsIgnoreCase("null"))) {
                insights.add(new Insight(Rule.PERMISSIVE_CORS, request.spanId(),
                        "CORS allows any origin with credentials (OWASP A02 Security Misconfiguration): "
                                + request.name() + " answered Access-Control-Allow-Origin: " + origin
                                + " with Access-Control-Allow-Credentials: true. List the allowed origins instead",
                        0));
                return;
            }
        }
    }

    // A07 Authentication Failures --------------------------------------------------------------

    private static void sessionCookies(List<TraceView.Row> serverRequests, List<Insight> insights) {
        Set<String> reported = new LinkedHashSet<>();
        for (TraceView.Row request : serverRequests) {
            String setCookies = responseHeaders(request).get("set-cookie");
            if (setCookies == null) {
                continue;
            }
            for (String cookie : setCookies.split("\n")) {
                int eq = cookie.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = cookie.substring(0, eq).trim();
                if (!SESSION_COOKIE.matcher(name).find() || !reported.add(name)) {
                    continue;
                }
                String flags = cookie.substring(eq).toLowerCase(Locale.ROOT);
                List<String> missing = new ArrayList<>();
                if (!flags.contains("httponly")) {
                    missing.add("HttpOnly");
                }
                if (!flags.contains("samesite")) {
                    missing.add("SameSite");
                }
                if (!missing.isEmpty()) {
                    insights.add(new Insight(Rule.INSECURE_SESSION_COOKIE, request.spanId(),
                            "Session cookie without " + String.join(" and ", missing)
                                    + " (OWASP A07 Authentication Failures): " + request.name() + " set " + name
                                    + ". Without HttpOnly a script can read it; without SameSite other sites can send it."
                                    + " Add the flags, and Secure where the site runs on HTTPS",
                            0));
                }
            }
        }
    }

    /** An unsigned token can be forged; one without an expiry is valid forever once it leaks. */
    private static void weakJwts(List<TraceView.Row> serverRequests, List<Insight> insights) {
        for (TraceView.Row request : serverRequests) {
            String authorization = request.attributes().get(REQUEST_HEADER + "authorization");
            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                continue;
            }
            String[] parts = authorization.substring(7).trim().split("\\.", -1);
            if (parts.length != 3) {
                continue;
            }
            String header = base64Json(parts[0]);
            String payload = base64Json(parts[1]);
            if (header == null || payload == null) {
                continue;
            }
            List<String> problems = new ArrayList<>();
            if (header.matches("(?is).*\"alg\"\\s*:\\s*\"none\".*") || parts[2].isEmpty()) {
                problems.add("is not signed (alg none)");
            }
            if (!payload.matches("(?s).*\"exp\"\\s*:.*")) {
                problems.add("has no expiry (exp)");
            }
            if (!problems.isEmpty()) {
                insights.add(new Insight(Rule.WEAK_JWT, request.spanId(),
                        "JWT " + String.join(" and ", problems) + " (OWASP A07 Authentication Failures): the bearer"
                                + " token sent to " + request.name() + ". Reject unsigned tokens and give every token"
                                + " a short expiry",
                        0));
                return;
            }
        }
    }

    // A04 Cryptographic Failures ---------------------------------------------------------------

    /** URLs end up in server logs, proxies and browser history. */
    private static void secretsInUrls(List<TraceView.Row> rows, List<Insight> insights) {
        Set<String> reported = new LinkedHashSet<>();
        for (TraceView.Row row : rows) {
            String query = row.attributes().get("url.query");
            if (query == null) {
                String full = row.attributes().get("url.full");
                int q = full == null ? -1 : full.indexOf('?');
                query = q < 0 ? null : full.substring(q + 1);
            }
            if (query == null) {
                continue;
            }
            List<String> keys = new ArrayList<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                if (isSensitiveKey(key) && reported.add(key)) {
                    keys.add(key);
                }
            }
            if (!keys.isEmpty()) {
                insights.add(new Insight(Rule.SECRET_IN_URL, row.spanId(),
                        "Secret in URL (OWASP A04 Cryptographic Failures): " + String.join(", ", keys) + " sent in the"
                                + " query string of " + row.name() + ". URLs are kept in logs, proxies and browser"
                                + " history; send it in a header or the body",
                        0));
            }
        }
    }

    /** accessToken, access_token and access-token are all the same word list. */
    static boolean isSensitiveKey(String key) {
        String snake = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return SENSITIVE_WORD.matcher(snake).find();
    }

    // A10 Mishandling of Exceptional Conditions ------------------------------------------------

    private static void errorDetails(List<TraceView.Row> serverRequests, List<Insight> insights) {
        for (TraceView.Row request : serverRequests) {
            String body = cap(request.attributes().get("http.response.body"));
            if (body == null) {
                continue;
            }
            String what = STACK_FRAME.matcher(body).find() ? "a stack trace"
                    : SQL_ERROR.matcher(body).find() ? "a database error" : null;
            if (what != null) {
                insights.add(new Insight(Rule.ERROR_DETAILS_EXPOSED, request.spanId(),
                        "Error details sent to the client (OWASP A10 Mishandling of Exceptional Conditions): the response"
                                + " to " + request.name() + " contains " + what + ". Log it on the server and answer"
                                + " with a generic message (server.error.include-stacktrace=never)",
                        0));
                return;
            }
        }
    }

    // A09 Logging failures: secrets in logs ----------------------------------------------------

    /** Credential headers whose values should never be logged or put in a URL. */
    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "x-api-key", "api-key");

    /**
     * A credential the client sent that appears, as text, in a log line of the same request. Logs
     * are kept and shipped far more widely than the app's memory, so a secret in them leaks.
     */
    private static void secretsInLogs(List<TraceView.Row> rows, List<TraceView.Row> serverRequests,
            Map<String, TraceView.Row> byId, List<Insight> insights) {
        for (TraceView.Row request : serverRequests) {
            Map<String, String> secrets = credentialValues(request);
            if (secrets.isEmpty()) {
                continue;
            }
            for (TraceView.Row log : rows) {
                if (log.kind() != SpanKind.LOG || !isUnder(log, request, byId)) {
                    continue;
                }
                String message = log.attributes().get("log.message");
                if (message == null) {
                    continue;
                }
                List<String> found = secrets.entrySet().stream()
                        .filter(e -> message.contains(e.getValue()))
                        .map(Map.Entry::getKey)
                        .distinct()
                        .toList();
                if (found.isEmpty()) {
                    continue;
                }
                insights.add(new Insight(Rule.SECRET_IN_LOG, log.spanId(),
                        "Secret written to a log (OWASP A09 Security Logging & Alerting Failures): " + String.join(", ", found)
                                + " from " + request.name() + " appears in a " + logName(log)
                                + ". Logs reach files, aggregators and support tickets; never log credentials",
                        0));
                return;
            }
        }
    }

    /** What the client sent that counts as a credential: field name (never the value) to the value. */
    private static Map<String, String> credentialValues(TraceView.Row request) {
        Map<String, String> secrets = new LinkedHashMap<>();
        request.attributes().forEach((key, value) -> {
            if (key.startsWith(REQUEST_HEADER) && CREDENTIAL_HEADERS.contains(key.substring(REQUEST_HEADER.length()))) {
                String name = key.substring(REQUEST_HEADER.length());
                String token = value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
                if (token.length() >= MIN_STRING_VALUE && !token.equals("[REDACTED]")) {
                    secrets.put("the " + name + " value", token);
                }
            }
        });
        requestInputs(request).forEach((field, value) -> {
            if (isSensitiveKey(bareName(field)) && value.length() >= MIN_STRING_VALUE) {
                secrets.put(field, value);
            }
        });
        return secrets;
    }

    private static String bareName(String field) {
        return field.substring(field.lastIndexOf(' ') + 1).replace("{", "").replace("}", "");
    }

    private static String logName(TraceView.Row log) {
        String level = log.attributes().getOrDefault("log.level", "log");
        String logger = log.attributes().getOrDefault("log.logger", "");
        String shortLogger = logger.substring(logger.lastIndexOf('.') + 1);
        return (level + " log" + (shortLogger.isEmpty() ? "" : " from " + shortLogger)).trim();
    }

    // A04 Cryptographic Failures: secrets in the response --------------------------------------

    /** A response body field whose name says it holds a secret, sent with a real value. */
    private static void sensitiveFieldsInResponse(List<TraceView.Row> serverRequests, List<Insight> insights) {
        for (TraceView.Row request : serverRequests) {
            String body = cap(request.attributes().get("http.response.body"));
            if (body == null) {
                continue;
            }
            String trimmed = body.stripLeading();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                continue;
            }
            Set<String> fields = new LinkedHashSet<>();
            Matcher field = JSON_FIELD.matcher(body);
            while (field.find()) {
                String name = field.group(1);
                String value = field.group(2) != null ? field.group(2) : field.group(3);
                if (isSensitiveKey(name) && value != null && !value.isEmpty() && !value.equals("[REDACTED]")
                        && !value.equals("null")) {
                    fields.add(name);
                }
            }
            if (!fields.isEmpty()) {
                insights.add(new Insight(Rule.SENSITIVE_FIELD_IN_RESPONSE, request.spanId(),
                        "Secret in the response (OWASP A04 Cryptographic Failures): " + request.name() + " returned "
                                + String.join(", ", fields) + ". A field like this rarely belongs in an API response;"
                                + " leave it out of the DTO",
                        0));
                return;
            }
        }
    }

    // SSRF: request value steering an outbound call --------------------------------------------

    /**
     * A value the client sent that appears in the URL of an outbound HTTP call the server then made:
     * the caller can influence where the server connects (server-side request forgery).
     */
    private static void requestValuesSteeringOutboundCalls(List<TraceView.Row> rows, List<TraceView.Row> serverRequests,
            Map<String, TraceView.Row> byId, List<Insight> insights) {
        Set<String> reported = new LinkedHashSet<>();
        for (TraceView.Row request : serverRequests) {
            Map<String, String> inputs = requestInputs(request);
            if (inputs.isEmpty()) {
                continue;
            }
            for (TraceView.Row call : rows) {
                if (call.kind() != SpanKind.HTTP_CLIENT || !isUnder(call, request, byId)) {
                    continue;
                }
                String url = outboundUrl(call);
                if (url == null) {
                    continue;
                }
                String decoded = decode(url);
                List<String> fields = inputs.entrySet().stream()
                        // Only values long enough to be a host, path or id, not a "1" that matches any URL.
                        .filter(e -> e.getValue().length() >= 4 && decoded.contains(e.getValue()))
                        .map(Map.Entry::getKey)
                        .distinct()
                        .toList();
                if (fields.isEmpty() || !reported.add(call.name() + "|" + String.join(",", fields))) {
                    continue;
                }
                insights.add(new Insight(Rule.REQUEST_VALUE_IN_OUTBOUND_URL, call.spanId(),
                        "Request value steers an outbound call (OWASP A01 Broken Access Control, SSRF): " + String.join(", ", fields)
                                + " from " + request.name() + " is in the URL of " + call.name()
                                + ". A caller may be able to make the server fetch an address of their choosing;"
                                + " validate it against an allowlist of hosts",
                        0));
            }
        }
    }

    private static String outboundUrl(TraceView.Row call) {
        String full = call.attributes().get("url.full");
        if (full != null) {
            return full;
        }
        String address = call.attributes().get("server.address");
        String template = call.attributes().get("url.template");
        if (address != null && template != null) {
            return address + template;
        }
        return template;
    }

    // Helpers ----------------------------------------------------------------------------------

    private static Map<String, String> responseHeaders(TraceView.Row request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.attributes().forEach((key, value) -> {
            if (key.startsWith(RESPONSE_HEADER)) {
                headers.put(key.substring(RESPONSE_HEADER.length()).toLowerCase(Locale.ROOT), value);
            }
        });
        return headers;
    }

    private static boolean isUnder(TraceView.Row row, TraceView.Row ancestor, Map<String, TraceView.Row> byId) {
        for (TraceView.Row up = byId.get(row.parentSpanId()); up != null; up = byId.get(up.parentSpanId())) {
            if (up == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static String base64Json(String part) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8).trim();
            return json.startsWith("{") ? json : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The first {@value #MAX_SCAN_CHARS} characters, so a regex never runs over a huge body; null stays null. */
    private static String cap(String value) {
        return value == null || value.length() <= MAX_SCAN_CHARS ? value : value.substring(0, MAX_SCAN_CHARS);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
    }
}
