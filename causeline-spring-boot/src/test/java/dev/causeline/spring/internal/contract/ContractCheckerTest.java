// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sun.net.httpserver.HttpServer;
import dev.causeline.core.Span;
import dev.causeline.core.SpanKind;
import dev.causeline.core.SpanStatus;
import dev.causeline.core.TraceStore;
import dev.causeline.spring.internal.contract.ContractChecker.Finding;
import dev.causeline.spring.internal.contract.ContractChecker.Kind;
import dev.causeline.spring.internal.contract.ContractService.Report;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContractCheckerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What springdoc generates for a small orders API: schemas by reference, one array, one enum. */
    private static final String SPEC = """
            {"openapi":"3.1.0","paths":{
              "/api/orders":{"post":{"responses":{"201":{"content":{"*/*":{"schema":{"$ref":"#/components/schemas/Order"}}}}}}},
              "/api/orders/{id}":{"get":{"responses":{
                "200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Order"}}}},
                "404":{"description":"no such order"}}}}},
             "components":{"schemas":{
              "Order":{"type":"object","required":["orderId","status"],"properties":{
                "orderId":{"type":"integer","format":"int64"},
                "status":{"type":"string","enum":["PAID","FAILED"]},
                "total":{"type":"number"},
                "note":{"type":["string","null"]},
                "lines":{"type":"array","items":{"$ref":"#/components/schemas/Line"}},
                "labels":{"type":"object","additionalProperties":{"type":"string"}}}},
              "Line":{"allOf":[{"$ref":"#/components/schemas/Priced"},
                {"type":"object","properties":{"sku":{"type":"string"}}}]},
              "Priced":{"type":"object","required":["price"],"properties":{"price":{"type":"integer"}}}}}}
            """;

    @Test
    void aResponseThatFitsTheDocumentHasNoFindings() {
        assertThat(check("POST", "/api/orders", 201, """
                {"orderId":7,"status":"PAID","total":12,"note":null,"lines":[{"sku":"book","price":1200}],
                 "labels":{"gift":"yes"}}""")).isEmpty();
        // A concrete path is matched to its template, and a body-less documented status is fine.
        assertThat(check("GET", "/api/orders/7", 200, "{\"orderId\":7,\"status\":\"FAILED\"}")).isEmpty();
        assertThat(check("GET", "/api/orders/{orderId}", 404, null)).isEmpty();
    }

    @Test
    void reportsUndocumentedFieldsWrongTypesMissingFieldsAndUnknownEnumValues() {
        List<Finding> findings = check("POST", "/api/orders", 201, """
                {"orderId":"7","status":"SHIPPED","internalCost":3,
                 "lines":[{"sku":"book","price":"12.00","margin":1},{"sku":"pen"}],
                 "labels":{"gift":true}}""");

        assertThat(findings).extracting(Finding::kind, Finding::path).containsExactlyInAnyOrder(
                tuple(Kind.WRONG_TYPE, "orderId"),
                tuple(Kind.NOT_IN_ENUM, "status"),
                tuple(Kind.UNDOCUMENTED_FIELD, "internalCost"),
                tuple(Kind.WRONG_TYPE, "lines[].price"),
                tuple(Kind.UNDOCUMENTED_FIELD, "lines[].margin"),
                tuple(Kind.MISSING_FIELD, "lines[].price"),
                tuple(Kind.WRONG_TYPE, "labels.gift"));
        assertThat(findings).extracting(Finding::message).contains(
                "orderId is documented as integer, but the response has a string",
                "internalCost is in the response, but not in the document",
                "status is not one of the documented values (PAID, FAILED)");
    }

    @Test
    void reportsOperationsAndStatusesTheDocumentDoesNotHave() {
        assertThat(check("DELETE", "/api/orders/{id}", 204, null)).singleElement()
                .isEqualTo(new Finding(Kind.UNDOCUMENTED_OPERATION, "", "DELETE /api/orders/{id} is not in the OpenAPI document"));
        assertThat(check("GET", "/api/orders/{id}", 500, "{\"error\":\"boom\"}")).singleElement()
                .isEqualTo(new Finding(Kind.UNDOCUMENTED_STATUS, "",
                        "Status 500 is not documented for GET /api/orders/{id} (documented: 200, 404)"));
        assertThat(check("GET", "/api/orders/{id}", 404, "{\"error\":\"gone\"}")).singleElement()
                .satisfies(f -> assertThat(f.message()).startsWith("The response has a JSON body, but the document describes none"));
    }

    @Test
    void comparedWithAnEarlierRunOnlyRemovedFieldsAndChangedTypesAreBreaking() {
        List<Finding> breaking = ContractChecker.breaking(
                json("{\"orderId\":7,\"total\":12,\"status\":\"PAID\",\"lines\":[{\"sku\":\"book\",\"price\":1200}],\"note\":null}"),
                json("{\"orderId\":\"7\",\"total\":12.5,\"lines\":[{\"sku\":\"book\"}],\"note\":\"x\",\"extra\":1}"));

        assertThat(breaking).extracting(Finding::kind, Finding::path).containsExactly(
                tuple(Kind.TYPE_CHANGED, "orderId"),
                tuple(Kind.FIELD_REMOVED, "status"),
                tuple(Kind.FIELD_REMOVED, "lines[].price"));
        assertThat(breaking.getFirst().message()).isEqualTo("orderId was an integer in the earlier run and is a string now");
    }

    @Test
    void theServiceReadsTheDocumentOverHttpAndFindsTheEarlierRun() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v3/api-docs", exchange -> {
            byte[] body = SPEC.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            TraceStore store = new TraceStore();
            store.add(request("a1f92f3577b34da6a3ce929d0e0e4736", 100, "{\"orderId\":1,\"status\":\"PAID\",\"total\":12}"));
            store.add(request("a2f92f3577b34da6a3ce929d0e0e4736", 200, "{\"orderId\":2,\"status\":\"PAID\",\"debug\":true}"));

            Report report = new ContractService(store, () -> base + "/v3/api-docs")
                    .check("a2f92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");

            assertThat(report.specProblem()).isNull();
            assertThat(report.findings()).extracting(Finding::kind, Finding::path)
                    .containsExactly(tuple(Kind.UNDOCUMENTED_FIELD, "debug"));
            assertThat(report.baseline().traceId()).isEqualTo("a1f92f3577b34da6a3ce929d0e0e4736");
            assertThat(report.breaking()).extracting(Finding::kind, Finding::path)
                    .containsExactly(tuple(Kind.FIELD_REMOVED, "total"));

            // No document: the comparison with the earlier run is still made, and the reason is given.
            Report without = new ContractService(store, () -> base + "/nothing-here")
                    .check("a2f92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
            assertThat(without.specProblem()).contains("No OpenAPI document at").contains("springdoc-openapi");
            assertThat(without.breaking()).hasSize(1);
            // The first run has nothing earlier to compare with.
            assertThat(new ContractService(store, () -> base + "/v3/api-docs")
                    .check("a1f92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7").baseline()).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static Span request(String traceId, long start, String responseBody) {
        return new Span(traceId, "00f067aa0ba902b7", null, SpanKind.REQUEST, "POST /api/orders", "checkout-demo", start, 1,
                SpanStatus.OK, Map.of("http.request.method", "POST", "http.route", "/api/orders",
                        "http.response.status_code", "201", "http.response.body", responseBody));
    }

    private static List<Finding> check(String method, String route, int status, String body) {
        return ContractChecker.check(json(SPEC), method, route, status, body == null ? null : json(body));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
