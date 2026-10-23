// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import jakarta.annotation.PostConstruct;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * INTENTIONALLY INSECURE. These endpoints exist only so the Causeline demo can show each of the
 * security checks (PRD section 7) firing on a real trace. They are gated to the {@code dev} profile
 * and live in the example app, never in a published artifact. Do not copy any of this into real
 * code: each endpoint is a textbook mistake, labelled with the OWASP category it demonstrates.
 */
@RestController
@RequestMapping("/api/scenarios")
@Profile("dev")
class SecurityScenariosController {

    private static final Logger log = LoggerFactory.getLogger(SecurityScenariosController.class);

    private final JdbcTemplate jdbc;
    private final RestClient.Builder http;
    private final Environment environment;

    SecurityScenariosController(JdbcTemplate jdbc, RestClient.Builder http, Environment environment) {
        this.jdbc = jdbc;
        this.http = http;
        this.environment = environment;
    }

    /** Customers the probe targets work on; the table is created here because the demo has no schema file for it. */
    @PostConstruct
    void seedCustomers() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS demo_customers (id BIGINT PRIMARY KEY, name VARCHAR(100), "
                + "email VARCHAR(100), role VARCHAR(100))");
        jdbc.update("MERGE INTO demo_customers (id, name, email, role) KEY (id) VALUES (1001, 'Ada Lovelace', 'ada@example.com', 'customer')");
        jdbc.update("MERGE INTO demo_customers (id, name, email, role) KEY (id) VALUES (1002, 'Grace Hopper', 'grace@example.com', 'customer')");
    }

    /**
     * A05 Injection. The search term is concatenated into the SQL instead of bound, so Causeline
     * sees the value in the prepared statement text. A real search would use a bound parameter.
     */
    @GetMapping("/search")
    List<Map<String, Object>> search(@RequestParam("item") String item) {
        return jdbc.queryForList("SELECT id, item, status FROM orders WHERE item = '" + item + "'");
    }

    /**
     * A04 Cryptographic Failures. The response carries secret-looking fields that should never be
     * serialized back to a client.
     */
    @GetMapping("/user")
    Map<String, Object> user() {
        return Map.of("id", 7, "name", "Ada Lovelace", "email", "ada@example.com",
                "password", "hunter2", "passwordHash", "$2a$10$Nq9demohashvalue", "apiKey", "sk_live_demo_0xDEADBEEF");
    }

    /**
     * A09 Logging Failures (the credential from the request is logged) and A07 Authentication
     * Failures (the session cookie has no HttpOnly or SameSite). Send an Authorization header to
     * see the A09 finding; the demo page does.
     */
    @GetMapping("/login")
    ResponseEntity<Map<String, Object>> login(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        // Logging a credential: it ends up in the log file, which Causeline flags (A09).
        log.warn("Authenticating caller with credentials {}", authorization);
        return ResponseEntity.ok()
                // No HttpOnly, no SameSite: a script can read it and another site can send it (A07).
                .header(HttpHeaders.SET_COOKIE, "SESSIONID=demo-session-" + System.nanoTime() + "; Path=/")
                .body(Map.of("status", "logged in"));
    }

    /**
     * A02 Security Misconfiguration. The response discloses the server version and allows any
     * origin together with credentials.
     */
    @GetMapping("/legacy")
    ResponseEntity<Map<String, Object>> legacy() {
        return ResponseEntity.ok()
                .header("Server", "Apache/2.4.1 (Unix)")
                .header("X-Powered-By", "PHP/5.6.40")
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Credentials", "true")
                .body(Map.of("status", "ok"));
    }

    /**
     * SSRF (A01 Broken Access Control). The caller's value decides part of the URL the server then fetches. This demo
     * keeps the call on localhost so it is safe to run, but a real version taking a full URL would
     * let a caller reach internal addresses; Causeline flags the request value in the outbound URL.
     */
    @GetMapping("/fetch")
    Map<String, Object> fetch(@RequestParam("resource") String resource) {
        String baseUrl = "http://localhost:" + environment.getProperty("local.server.port", "8080");
        String status = http.baseUrl(baseUrl).build()
                .get()
                .uri("/fake-payment/ping/" + resource)
                .retrieve()
                .body(String.class);
        return Map.of("fetched", "/fake-payment/ping/" + resource, "status", String.valueOf(status));
    }

    /**
     * A10 Mishandling of Exceptional Conditions. The handler returns the exception's stack trace in
     * the response body, telling a caller about the server's internals.
     */
    @GetMapping("/boom")
    ResponseEntity<Map<String, Object>> boom() {
        try {
            throw new IllegalStateException("database connection pool exhausted");
        } catch (IllegalStateException e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            return ResponseEntity.status(500).body(Map.of("error", "Internal error", "trace", trace.toString()));
        }
    }
    // ---- Probe targets: what the active probes (IDOR, mass assignment, open redirect, auth boundary) find ----

    /**
     * Broken access control (A01), probed by "Another ID". The record is returned for any ID: there
     * is no check that the caller owns it, so changing 1001 to 1002 reads someone else's data.
     */
    @GetMapping("/customers/{id}")
    ResponseEntity<Map<String, Object>> customer(@PathVariable("id") long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, email, role FROM demo_customers WHERE id = ?", id);
        return rows.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(rows.get(0));
    }

    private static final List<String> COLUMNS = List.of("name", "email", "role");

    /**
     * Mass assignment (A01), probed by "Extra field". Every column the client names is written,
     * including {@code role}, which only an administrator should be able to change. The values are
     * bound, so this is not SQL injection; the flaw is that the client chooses which columns change.
     */
    @PostMapping("/customers/{id}/update")
    Map<String, Object> update(@PathVariable("id") long id, @RequestBody Map<String, Object> changes) {
        List<String> columns = changes.keySet().stream().filter(COLUMNS::contains).toList();
        if (!columns.isEmpty()) {
            List<Object> arguments = new ArrayList<>(columns.stream().map(changes::get).toList());
            arguments.add(id);
            jdbc.update("UPDATE demo_customers SET " + columns.stream().map(c -> c + " = ?").collect(Collectors.joining(", "))
                    + " WHERE id = ?", arguments.toArray());
        }
        return customer(id).getBody();
    }

    /**
     * Open redirect (A01), probed by "Redirect elsewhere". The target comes straight from the
     * request, so a link on this site can send people to any other.
     */
    @GetMapping("/redirect")
    ResponseEntity<Void> redirect(@RequestParam(value = "next", defaultValue = "/") String next) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, next).build();
    }

    /**
     * An auth boundary that isn't one (A01), probed by "Without a header". The tenant header is
     * accepted but never checked against the caller, and the answer is the same without it.
     */
    @GetMapping("/tenant-data")
    Map<String, Object> tenantData(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        return Map.of("invoices", List.of("INV-1", "INV-2"), "note", "the same for every tenant");
    }
}
