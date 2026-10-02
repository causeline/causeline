// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders method arguments and return values as compact JSON for controller, service and
 * repository spans.
 *
 * <p>It is built to be safe on a request thread of someone else's application:
 * <ul>
 *   <li>it never triggers lazy loading: a Hibernate proxy or collection that is not loaded yet is
 *       shown as {@code "[not loaded]"}, so rendering can't add queries or throw;</li>
 *   <li>infrastructure such as servlet requests, streams, uploaded files and security principals is
 *       shown by type only;</li>
 *   <li>depth, collection sizes and the total length are capped, and cycles are cut;</li>
 *   <li>any failure renders the value's type instead of failing the request;</li>
 *   <li>keys listed in {@code causeline.capture.redact-keys} are hidden here too.</li>
 * </ul>
 */
public final class ValueRenderer {

    public static final int MAX_CHARS = 4_096;
    static final int MAX_DEPTH = 4;
    static final int MAX_ITEMS = 25;
    static final int MAX_STRING = 500;

    /** Shown by type only: streams, files and other JDK resources (matched exactly, with subtypes). */
    private static final Set<String> OPAQUE_TYPES = Set.of(
            "java.io.InputStream", "java.io.OutputStream", "java.io.Reader", "java.io.Writer", "java.io.File",
            "java.nio.file.Path", "java.nio.channels.Channel", "java.sql.Connection", "java.sql.ResultSet",
            "java.security.Principal", "java.lang.Thread", "java.lang.Class", "java.lang.ClassLoader");

    /** Shown by type only: request plumbing, uploaded files, security and framework objects. */
    private static final List<String> OPAQUE_PREFIXES = List.of(
            "jakarta.servlet.", "javax.servlet.", "org.springframework.web.multipart.", "org.springframework.security.",
            "org.springframework.ui.", "org.springframework.validation.", "org.springframework.core.io.",
            "org.springframework.web.context.request.", "org.springframework.http.server.",
            "org.springframework.web.servlet.", "org.springframework.context.", "org.springframework.beans.",
            "reactor.core.", "java.lang.reflect.");

    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();
    private static final MethodHandle IS_INITIALIZED = hibernate("isInitialized", MethodType.methodType(boolean.class, Object.class));
    private static final MethodHandle UNPROXY = hibernate("unproxy", MethodType.methodType(Object.class, Object.class));

    private final SensitiveData blocked;

    /** @param blocked keys hidden even locally ({@code causeline.capture.redact-keys}) */
    public ValueRenderer(SensitiveData blocked) {
        this.blocked = blocked;
    }

    /** {@code {"item":"book","quantity":1}}: arguments by parameter name ({@code arg0} without {@code -parameters}). */
    public String arguments(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        Writer out = new Writer();
        out.raw('{');
        for (int i = 0; i < args.length; i++) {
            String name = i < parameters.length ? parameters[i].getName() : "arg" + i;
            if (i > 0) {
                out.raw(',');
            }
            out.string(name);
            out.raw(':');
            if (blocked.isSensitiveKey(name)) {
                out.string(SensitiveData.REDACTED);
            } else {
                render(args[i], out, 0, newSeen());
            }
            if (out.full()) {
                break;
            }
        }
        out.raw('}');
        return out.result();
    }

    public String value(Object value) {
        Writer out = new Writer();
        render(value, out, 0, newSeen());
        return out.result();
    }

    private static Set<Object> newSeen() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private void render(Object value, Writer out, int depth, Set<Object> seen) {
        if (out.full()) {
            return;
        }
        if (value == null) {
            out.raw("null");
            return;
        }
        try {
            if (!isLoaded(value)) {
                out.string("[not loaded]");
                return;
            }
            value = unproxy(value);
            Class<?> type = value.getClass();
            if (value instanceof CharSequence || value instanceof Character) {
                out.string(truncate(value.toString()));
            } else if (value instanceof Number || value instanceof Boolean) {
                String text = value.toString();
                out.raw(text.equals("NaN") || text.contains("Infinity") ? "\"" + text + "\"" : text);
            } else if (value instanceof Enum<?> e) {
                out.string(e.name());
            } else if (value instanceof Optional<?> optional) {
                render(optional.orElse(null), out, depth, seen);
            } else if (value instanceof byte[] bytes) {
                out.string("[" + bytes.length + " bytes]");
            } else if (isOpaque(type)) {
                out.string("[" + type.getSimpleName() + "]");
            } else if (isPlainJdkValue(type)) {
                out.string(truncate(value.toString()));
            } else if (depth >= MAX_DEPTH) {
                out.string("[" + type.getSimpleName() + "]");
            } else if (!seen.add(value)) {
                out.string("[cycle]");
            } else if (value instanceof Map<?, ?> map) {
                map(map, out, depth, seen);
            } else if (value instanceof Collection<?> collection) {
                items(collection.iterator(), collection.size(), out, depth, seen);
            } else if (type.isArray()) {
                List<Object> list = new ArrayList<>();
                int length = Array.getLength(value);
                for (int i = 0; i < Math.min(length, MAX_ITEMS + 1); i++) {
                    list.add(Array.get(value, i));
                }
                items(list.iterator(), length, out, depth, seen);
            } else {
                object(value, type, out, depth, seen);
            }
        } catch (RuntimeException | LinkageError e) {
            out.string("[" + value.getClass().getSimpleName() + "]");
        }
    }

    private void map(Map<?, ?> map, Writer out, int depth, Set<Object> seen) {
        out.raw('{');
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (i == MAX_ITEMS) {
                out.raw(',');
                out.string("…");
                out.raw(':');
                out.string((map.size() - MAX_ITEMS) + " more");
                break;
            }
            if (i++ > 0) {
                out.raw(',');
            }
            String key = String.valueOf(entry.getKey());
            out.string(key);
            out.raw(':');
            if (blocked.isSensitiveKey(key)) {
                out.string(SensitiveData.REDACTED);
            } else {
                render(entry.getValue(), out, depth + 1, seen);
            }
        }
        out.raw('}');
    }

    private void items(Iterator<?> iterator, int size, Writer out, int depth, Set<Object> seen) {
        out.raw('[');
        int i = 0;
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (i == MAX_ITEMS) {
                out.raw(',');
                out.string("… " + (size - MAX_ITEMS) + " more");
                break;
            }
            if (i++ > 0) {
                out.raw(',');
            }
            render(item, out, depth + 1, seen);
        }
        out.raw(']');
    }

    private void object(Object value, Class<?> type, Writer out, int depth, Set<Object> seen) {
        out.raw('{');
        boolean first = true;
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                // Request records are often package-private, nested in the controller.
                accessor.trySetAccessible();
                first = field(component.getName(), () -> accessor.invoke(value), out, depth, seen, first);
            }
        } else {
            for (Field field : fields(type)) {
                first = field(field.getName(), () -> field.get(value), out, depth, seen, first);
            }
        }
        out.raw('}');
    }

    private interface Getter {
        Object get() throws ReflectiveOperationException;
    }

    private boolean field(String name, Getter getter, Writer out, int depth, Set<Object> seen, boolean first) {
        if (out.full()) {
            return first;
        }
        if (!first) {
            out.raw(',');
        }
        out.string(name);
        out.raw(':');
        if (blocked.isSensitiveKey(name)) {
            out.string(SensitiveData.REDACTED);
            return false;
        }
        try {
            render(getter.get(), out, depth + 1, seen);
        } catch (ReflectiveOperationException | RuntimeException e) {
            out.string("[unreadable]");
        }
        return false;
    }

    /** Instance fields of the application's classes, superclasses included; JDK internals are left out. */
    private static List<Field> fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            List<Field> result = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class && !isJdk(c); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    // $$ fields belong to proxies and bytecode enhancers (Hibernate, CGLIB).
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()
                            || field.getName().contains("$$")) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        result.add(field);
                    } catch (RuntimeException e) {
                        // A module that doesn't open the package: skip the field.
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    private static boolean isOpaque(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (startsWithAny(c.getName())) {
                return true;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (startsWithAny(i.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean startsWithAny(String name) {
        if (OPAQUE_TYPES.contains(name)) {
            return true;
        }
        for (String prefix : OPAQUE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** JDK values such as dates, UUIDs and BigDecimal read best as their string form. */
    private static boolean isPlainJdkValue(Class<?> type) {
        return isJdk(type) && !Map.class.isAssignableFrom(type) && !Collection.class.isAssignableFrom(type)
                && !type.isArray() && !type.isRecord();
    }

    private static boolean isJdk(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static String truncate(String text) {
        return text.length() <= MAX_STRING ? text : text.substring(0, MAX_STRING) + "…";
    }

    private static boolean isLoaded(Object value) {
        if (IS_INITIALIZED == null) {
            return true;
        }
        try {
            return (boolean) IS_INITIALIZED.invokeExact(value);
        } catch (Throwable e) {
            return true;
        }
    }

    private static Object unproxy(Object value) {
        if (UNPROXY == null || !value.getClass().getName().contains("$HibernateProxy")) {
            return value;
        }
        try {
            return (Object) UNPROXY.invokeExact(value);
        } catch (Throwable e) {
            return value;
        }
    }

    /** Hibernate is optional: without it, nothing is lazy. */
    private static MethodHandle hibernate(String name, MethodType type) {
        try {
            Class<?> hibernate = Class.forName("org.hibernate.Hibernate", false, ValueRenderer.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(hibernate, name, type);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** A JSON writer that stops at {@link #MAX_CHARS} and closes what it opened. */
    private static final class Writer {
        private final StringBuilder json = new StringBuilder(128);
        private boolean truncated;

        boolean full() {
            if (json.length() >= MAX_CHARS) {
                truncated = true;
            }
            return truncated;
        }

        void raw(char c) {
            json.append(c);
        }

        void raw(String s) {
            json.append(s);
        }

        void string(String s) {
            json.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            json.append(String.format("\\u%04x", (int) c));
                        } else {
                            json.append(c);
                        }
                    }
                }
            }
            json.append('"');
        }

        String result() {
            // Past the limit the JSON may be cut mid-way; mark it rather than pretend it is complete.
            return truncated ? json.substring(0, Math.min(json.length(), MAX_CHARS)) + "… [truncated]" : json.toString();
        }
    }
}
