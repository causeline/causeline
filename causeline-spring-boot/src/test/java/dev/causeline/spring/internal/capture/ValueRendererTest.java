// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ValueRendererTest {

    private final ValueRenderer renderer = new ValueRenderer(SensitiveData.userBlocked(List.of("coupon")));

    record OrderRequest(String item, int quantity, BigDecimal price, LocalDate deliverBy) {
    }

    enum Status { PAID }

    static class Customer {
        private final String name = "Ada";
        private final String password = "hunter2";
        private static final String CONSTANT = "never shown";
        private transient String cache = "never shown";
        private Customer friend;
    }

    @SuppressWarnings("unused")
    static void place(OrderRequest request, String coupon, jakarta.servlet.http.HttpServletRequest http) {
    }

    @Test
    void argumentsAreNamedAndRenderedAsJson() throws Exception {
        Method method = ValueRendererTest.class.getDeclaredMethod("place", OrderRequest.class, String.class,
                jakarta.servlet.http.HttpServletRequest.class);

        String json = renderer.arguments(method, new Object[] {
                new OrderRequest("book", 2, new BigDecimal("19.90"), LocalDate.of(2026, 10, 3)), "SAVE10", null});

        assertThat(json).isEqualTo("{\"request\":{\"item\":\"book\",\"quantity\":2,\"price\":19.90,"
                + "\"deliverBy\":\"2026-10-03\"},\"coupon\":\"[REDACTED]\",\"http\":null}");
    }

    @Test
    void objectsShowTheirFieldsButNotStaticOrTransientOnes() {
        String json = renderer.value(new Customer());

        assertThat(json).contains("\"name\":\"Ada\"", "\"password\":\"hunter2\"", "\"friend\":null")
                .doesNotContain("never shown");
    }

    @Test
    void cyclesAreCutAndDepthIsCapped() {
        Customer a = new Customer();
        a.friend = a;
        assertThat(renderer.value(a)).contains("\"friend\":\"[cycle]\"");

        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> current = deep;
        for (int i = 0; i < 10; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            current.put("level" + i, next);
            current = next;
        }
        assertThat(renderer.value(deep)).contains("[LinkedHashMap]").doesNotContain("level9");
    }

    @Test
    void largeCollectionsAndStringsAreShortened() {
        List<Integer> many = IntStream.range(0, 100).boxed().toList();
        assertThat(renderer.value(many)).contains("\"… 75 more\"").doesNotContain(",99");
        assertThat(renderer.value("x".repeat(2_000))).hasSizeLessThan(ValueRenderer.MAX_STRING + 10);

        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            huge.add("y".repeat(400));
        }
        String json = renderer.value(List.of(huge, huge, huge));
        assertThat(json).endsWith("… [truncated]").hasSizeLessThan(ValueRenderer.MAX_CHARS + 20);
    }

    @Test
    void infrastructureIsShownByTypeOnly() {
        assertThat(renderer.value(new ByteArrayInputStream(new byte[] {1, 2}))).isEqualTo("\"[ByteArrayInputStream]\"");
        assertThat(renderer.value(new byte[42])).isEqualTo("\"[42 bytes]\"");
        assertThat(renderer.value(Thread.currentThread())).startsWith("\"[");
    }

    @Test
    void simpleValuesReadNaturally() {
        assertThat(renderer.value(Status.PAID)).isEqualTo("\"PAID\"");
        assertThat(renderer.value(Optional.of(42))).isEqualTo("42");
        assertThat(renderer.value(Optional.empty())).isEqualTo("null");
        assertThat(renderer.value(Map.of("coupon", "SAVE10"))).isEqualTo("{\"coupon\":\"[REDACTED]\"}");
        assertThat(renderer.value("say \"hi\"\n")).isEqualTo("\"say \\\"hi\\\"\\n\"");
        assertThat(renderer.value(Double.NaN)).isEqualTo("\"NaN\"");
    }

    @Test
    void aFailingGetterIsShownAsUnreadableInsteadOfBreakingTheRequest() {
        record Exploding(String name) {
            @Override
            public String name() {
                throw new IllegalStateException("boom");
            }
        }

        assertThat(renderer.value(new Exploding("x"))).isEqualTo("{\"name\":\"[unreadable]\"}");
    }
}
