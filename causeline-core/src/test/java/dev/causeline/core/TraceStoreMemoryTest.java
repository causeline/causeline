// SPDX-License-Identifier: Apache-2.0
package dev.causeline.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PRD section 11: a full store at the default limits (1,000 traces, 64 MB) must fit in 64 MB of
 * heap. The store's byte count is an estimate; this measures the real heap. Run with
 * {@code ./mvnw -pl causeline-core -Pperf test}.
 */
@Tag("perf")
class TraceStoreMemoryTest {

    private static final long BUDGET = 64L * 1024 * 1024;

    @Test
    void manyTypicalTracesFitTheHeapBudget() {
        // 1,000 checkout-sized traces: 25 spans each, with headers, SQL and small bodies.
        assertFitsBudget(1_000, 25, 300);
    }

    @Test
    void tracesWithLargeBodiesFitTheHeapBudget() {
        // Bodies near the capture limits hit the byte budget long before the trace count.
        assertFitsBudget(5_000, 12, 16_000);
    }

    private static void assertFitsBudget(int traces, int spansPerTrace, int bodyChars) {
        long before = usedHeap();
        TraceStore store = new TraceStore();
        for (int t = 0; t < traces; t++) {
            String traceId = hex(32);
            for (int s = 0; s < spansPerTrace; s++) {
                store.add(span(traceId, s, bodyChars));
            }
        }
        long used = usedHeap() - before;

        System.out.printf("[perf] store: %d traces kept, estimated %.1f MB, measured %.1f MB of heap%n",
                store.size(), store.estimatedBytes() / 1048576.0, used / 1048576.0);
        assertThat(store.size()).isPositive();
        assertThat(used).as("heap used by a full store (bytes)").isLessThanOrEqualTo(BUDGET);
    }

    private static Span span(String traceId, int index, int bodyChars) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("http.request.method", "POST");
        attributes.put("http.route", "/api/orders/{id}");
        attributes.put("http.request.header.authorization", "Bearer " + hex(64));
        attributes.put("http.request.header.user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0");
        attributes.put("db.query.text", "select o1_0.id,o1_0.item,o1_0.quantity,o1_0.status from orders o1_0 where o1_0.id="
                + index);
        attributes.put("http.request.body", "{\"item\":\"" + "x".repeat(bodyChars / 2) + "\"}");
        attributes.put("http.response.body", "{\"status\":\"" + "y".repeat(bodyChars / 2) + "\"}");
        return new Span(traceId, hex(16), index == 0 ? null : "00f067aa0ba902b7", SpanKind.SERVICE,
                "OrderService.createOrder", "checkout-demo", 1_790_000_000_000_000_000L + index, 1_000_000,
                SpanStatus.OK, attributes);
    }

    private static String hex(int length) {
        StringBuilder out = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            out.append(Character.forDigit(random.nextInt(16), 16));
        }
        return out.toString();
    }

    private static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
