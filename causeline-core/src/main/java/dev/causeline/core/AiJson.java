// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON in AI span attributes. This module has no JSON library, and these values are small
 * and simple. An object is a {@link Map}, an array a {@link List}, a number a {@link Double}.
 */
final class AiJson {

    private static final int MAX_DEPTH = 8;

    private final String text;
    private int at;

    private AiJson(String text) {
        this.text = text;
    }

    /** Null for anything that isn't JSON, such as a value withheld on export. */
    static Object parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            AiJson json = new AiJson(text);
            Object value = json.value(0);
            json.spaces();
            return json.at == text.length() ? value : null;
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

    /** The objects in a JSON array; empty for anything else. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> object) {
                objects.add((Map<String, Object>) object);
            }
        }
        return objects;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("too deep");
        }
        spaces();
        char first = text.charAt(at);
        return switch (first) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> object = new LinkedHashMap<>();
        at++;
        spaces();
        if (text.charAt(at) == '}') {
            at++;
            return object;
        }
        while (true) {
            spaces();
            String key = string();
            expect(':');
            object.put(key, value(depth + 1));
            spaces();
            char next = text.charAt(at++);
            if (next == '}') {
                return object;
            }
            if (next != ',') {
                throw new IllegalArgumentException("expected , or }");
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> array = new ArrayList<>();
        at++;
        spaces();
        if (text.charAt(at) == ']') {
            at++;
            return array;
        }
        while (true) {
            array.add(value(depth + 1));
            spaces();
            char next = text.charAt(at++);
            if (next == ']') {
                return array;
            }
            if (next != ',') {
                throw new IllegalArgumentException("expected , or ]");
            }
        }
    }

    private String string() {
        if (text.charAt(at) != '"') {
            throw new IllegalArgumentException("expected a string");
        }
        at++;
        StringBuilder value = new StringBuilder();
        while (true) {
            char c = text.charAt(at++);
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            char escape = text.charAt(at++);
            switch (escape) {
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    value.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> value.append(escape);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        return Double.valueOf(text.substring(start, at));
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw new IllegalArgumentException("expected " + word);
        }
        at += word.length();
        return value;
    }

    private void expect(char expected) {
        spaces();
        if (text.charAt(at++) != expected) {
            throw new IllegalArgumentException("expected " + expected);
        }
    }

    private void spaces() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}
