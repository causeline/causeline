// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Compares a recorded JSON response with what the application's OpenAPI document says it returns,
 * and with what an earlier run returned. Rule-based and deliberately lenient: it reports what a
 * client of the API would trip over, not every way a document can be imprecise.
 *
 * <ul>
 *   <li>{@code null} is accepted for any field: generated documents rarely mark what may be null.</li>
 *   <li>A field counts as undocumented only where the document lists the object's properties.</li>
 *   <li>Formats, patterns and number ranges are not checked.</li>
 * </ul>
 */
public final class ContractChecker {

    public enum Kind {
        /** The document has no such path and method. */
        UNDOCUMENTED_OPERATION,
        /** The operation is documented, but not with this status code. */
        UNDOCUMENTED_STATUS,
        /** The response has a field the document doesn't list. */
        UNDOCUMENTED_FIELD,
        /** A value's JSON type differs from the documented one. */
        WRONG_TYPE,
        /** A field the document marks as required is absent. */
        MISSING_FIELD,
        /** A value is not one of the documented enum values. */
        NOT_IN_ENUM,
        /** An earlier run's response had this field; this one doesn't. */
        FIELD_REMOVED,
        /** The field's JSON type differs from the earlier run's. */
        TYPE_CHANGED
    }

    /** @param path where in the body, e.g. {@code items[].price}; empty for the body or the operation itself */
    public record Finding(Kind kind, String path, String message) {
    }

    private static final int MAX_FINDINGS = 50;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_ITEMS = 20;

    private final JsonNode spec;
    private final Set<Finding> findings = new LinkedHashSet<>();

    private ContractChecker(JsonNode spec) {
        this.spec = spec;
    }

    /**
     * @param spec   the OpenAPI document (3.0 or 3.1)
     * @param route  the route template the request matched, e.g. {@code /api/orders/{id}}, or its path
     * @param body   the response body, or null when it is absent or not JSON
     */
    public static List<Finding> check(JsonNode spec, String method, String route, int status, JsonNode body) {
        ContractChecker checker = new ContractChecker(spec);
        checker.response(method, route, status, body);
        return List.copyOf(checker.findings);
    }

    /** What a client written against the earlier response would no longer find: removed fields and changed types. */
    public static List<Finding> breaking(JsonNode before, JsonNode after) {
        ContractChecker checker = new ContractChecker(null);
        checker.shape(before, after, "", 0);
        return List.copyOf(checker.findings);
    }

    private void response(String method, String route, int status, JsonNode body) {
        String operation = method + " " + route;
        JsonNode item = pathItem(route);
        JsonNode documented = item == null ? null : item.get(method.toLowerCase(Locale.ROOT));
        if (documented == null) {
            add(Kind.UNDOCUMENTED_OPERATION, "", operation + " is not in the OpenAPI document");
            return;
        }
        JsonNode responses = documented.path("responses");
        JsonNode response = first(responses.get(Integer.toString(status)), responses.get(status / 100 + "XX"),
                responses.get("default"));
        if (response == null) {
            add(Kind.UNDOCUMENTED_STATUS, "", "Status " + status + " is not documented for " + operation
                    + " (documented: " + String.join(", ", responses.propertyNames()) + ")");
            return;
        }
        if (body == null) {
            return;
        }
        JsonNode content = resolve(response).path("content");
        JsonNode media = content.get("application/json");
        for (Map.Entry<String, JsonNode> entry : content.properties()) {
            if (media == null && (entry.getKey().contains("json") || entry.getKey().equals("*/*"))) {
                media = entry.getValue();
            }
        }
        if (media == null) {
            add(Kind.UNDOCUMENTED_FIELD, "", "The response has a JSON body, but the document describes none for status "
                    + status + " of " + operation);
            return;
        }
        validate(body, media.path("schema"), "", 0);
    }

    /** The path item for a route: by its template, or, for a concrete path, by the template it fits. */
    private JsonNode pathItem(String route) {
        JsonNode paths = spec.path("paths");
        if (paths.has(route)) {
            return paths.get(route);
        }
        String wanted = trim(route).replaceAll("\\{[^}]*}", "{}");
        for (Map.Entry<String, JsonNode> entry : paths.properties()) {
            String template = trim(entry.getKey());
            String pattern = ("\\Q" + template.replaceAll("\\{[^}]*}", "\\\\E[^/]+\\\\Q") + "\\E");
            if (template.replaceAll("\\{[^}]*}", "{}").equals(wanted) || trim(route).matches(pattern)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String trim(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private void validate(JsonNode value, JsonNode rawSchema, String path, int depth) {
        JsonNode schema = resolve(rawSchema);
        if (schema.isMissingNode() || !schema.isObject() || value.isNull() || depth > MAX_DEPTH
                || findings.size() >= MAX_FINDINGS) {
            return;
        }
        for (String choice : List.of("oneOf", "anyOf")) {
            if (schema.path(choice).isArray() && schema.path(choice).size() > 0) {
                alternatives(value, schema.path(choice), path, depth);
            }
        }
        Shape shape = new Shape();
        collect(schema, shape, 0);
        String actual = typeOf(value);
        if (!shape.types.isEmpty() && !shape.types.contains(actual)
                && !(actual.equals("integer") && shape.types.contains("number"))) {
            add(Kind.WRONG_TYPE, path, at(path) + " is documented as " + String.join(" or ", shape.types) + ", but the response has "
                    + article(actual));
            return;
        }
        if (!shape.enumValues.isEmpty() && !value.isObject() && !value.isArray() && !shape.enumValues.contains(value.asString())) {
            add(Kind.NOT_IN_ENUM, path, at(path) + " is not one of the documented values (" + String.join(", ", shape.enumValues) + ")");
        }
        if (value.isObject()) {
            object(value, shape, path, depth);
        } else if (value.isArray() && shape.items != null) {
            int checked = 0;
            for (JsonNode element : value.values()) {
                if (checked++ >= MAX_ITEMS) {
                    break;
                }
                validate(element, shape.items, path + "[]", depth + 1);
            }
        }
    }

    private void object(JsonNode value, Shape shape, String path, int depth) {
        for (String required : shape.required) {
            if (!value.has(required)) {
                add(Kind.MISSING_FIELD, join(path, required), at(join(path, required)) + " is required by the document, but the response doesn't have it");
            }
        }
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            String at = join(path, field.getKey());
            JsonNode property = shape.properties.get(field.getKey());
            if (property != null) {
                validate(field.getValue(), property, at, depth + 1);
            } else if (shape.additional != null) {
                validate(field.getValue(), shape.additional, at, depth + 1);
            } else if (!shape.properties.isEmpty() && !shape.open) {
                add(Kind.UNDOCUMENTED_FIELD, at, at(at) + " is in the response, but not in the document");
            }
        }
    }

    /** Passes when one alternative fits; otherwise reports against the closest one. */
    private void alternatives(JsonNode value, JsonNode choices, String path, int depth) {
        List<Finding> closest = null;
        for (JsonNode choice : choices.values()) {
            ContractChecker attempt = new ContractChecker(spec);
            attempt.validate(value, choice, path, depth + 1);
            if (attempt.findings.isEmpty()) {
                return;
            }
            if (closest == null || attempt.findings.size() < closest.size()) {
                closest = new ArrayList<>(attempt.findings);
            }
        }
        if (closest != null) {
            closest.forEach(f -> add(f.kind(), f.path(), f.message()));
        }
    }

    /** What a schema says once its {@code $ref} and {@code allOf} parts are put together. */
    private static final class Shape {
        final Set<String> types = new LinkedHashSet<>();
        final Map<String, JsonNode> properties = new LinkedHashMap<>();
        final Set<String> required = new LinkedHashSet<>();
        final Set<String> enumValues = new LinkedHashSet<>();
        JsonNode items;
        /** A schema every other field must fit. */
        JsonNode additional;
        /** {@code additionalProperties: true}: other fields are expected. */
        boolean open;
    }

    private void collect(JsonNode rawSchema, Shape shape, int depth) {
        JsonNode schema = resolve(rawSchema);
        if (!schema.isObject() || depth > MAX_DEPTH) {
            return;
        }
        JsonNode type = schema.path("type");
        if (type.isString()) {
            shape.types.add(type.asString());
        } else if (type.isArray()) {
            // OpenAPI 3.1: ["string", "null"]. Null is accepted anyway.
            type.values().forEach(t -> {
                if (!"null".equals(t.asString())) {
                    shape.types.add(t.asString());
                }
            });
        }
        schema.path("properties").properties().forEach(p -> shape.properties.putIfAbsent(p.getKey(), p.getValue()));
        schema.path("required").values().forEach(r -> shape.required.add(r.asString()));
        schema.path("enum").values().forEach(e -> shape.enumValues.add(e.asString()));
        if (schema.has("items")) {
            shape.items = schema.get("items");
        }
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isObject()) {
            shape.additional = additional;
        } else if (additional.isBoolean() && additional.asBoolean()) {
            shape.open = true;
        }
        schema.path("allOf").values().forEach(part -> collect(part, shape, depth + 1));
    }

    /** Follows {@code $ref: "#/components/schemas/Order"} within the document. */
    private JsonNode resolve(JsonNode schema) {
        JsonNode current = schema;
        for (int hops = 0; current != null && current.has("$ref") && hops < 10; hops++) {
            String ref = current.get("$ref").asString();
            if (!ref.startsWith("#/")) {
                return MissingNode.getInstance(); // a reference to another file: nothing to check against
            }
            JsonNode target = spec;
            for (String part : ref.substring(2).split("/")) {
                target = target.path(part.replace("~1", "/").replace("~0", "~"));
            }
            current = target;
        }
        return current == null ? schema : current;
    }

    private void shape(JsonNode before, JsonNode after, String path, int depth) {
        if (before == null || after == null || before.isNull() || after.isNull() || depth > MAX_DEPTH
                || findings.size() >= MAX_FINDINGS) {
            return;
        }
        String was = typeOf(before);
        String now = typeOf(after);
        Set<String> numbers = Set.of("integer", "number");
        boolean bothNumbers = numbers.contains(was) && numbers.contains(now);
        if (!was.equals(now) && !bothNumbers) {
            add(Kind.TYPE_CHANGED, path, at(path) + " was " + article(was) + " in the earlier run and is " + article(now) + " now");
        } else if (before.isObject()) {
            for (Map.Entry<String, JsonNode> field : before.properties()) {
                String at = join(path, field.getKey());
                if (after.has(field.getKey())) {
                    shape(field.getValue(), after.get(field.getKey()), at, depth + 1);
                } else {
                    add(Kind.FIELD_REMOVED, at, at(at) + " was in the earlier run's response and is gone now");
                }
            }
        } else if (before.isArray() && before.size() > 0 && after.size() > 0) {
            shape(before.get(0), after.get(0), path + "[]", depth + 1);
        }
    }

    private void add(Kind kind, String path, String message) {
        if (findings.size() < MAX_FINDINGS) {
            findings.add(new Finding(kind, path, message));
        }
    }

    private static String typeOf(JsonNode value) {
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isNumber()) {
            return value.isIntegralNumber() ? "integer" : "number";
        }
        return value.isNull() ? "null" : "string";
    }

    private static String article(String type) {
        return (type.matches("[aeio].*") ? "an " : "a ") + type;
    }

    private static String at(String path) {
        return path.isEmpty() ? "The body" : path;
    }

    private static String join(String path, String field) {
        return path.isEmpty() ? field : path + "." + field;
    }

    private static JsonNode first(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
