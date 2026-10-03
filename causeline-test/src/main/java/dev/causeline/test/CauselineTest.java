// SPDX-License-Identifier: Apache-2.0
package dev.causeline.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.TestPropertySource;

/**
 * Records the traces a Spring Boot test produces, so the test can assert on what the application
 * did and not only on what it answered. Put it next to {@code @SpringBootTest} and take a
 * {@link RecordedTraces} parameter:
 *
 * {@snippet :
 * @SpringBootTest
 * @AutoConfigureMockMvc
 * @CauselineTest
 * class OrdersTest {
 *
 *     @Autowired
 *     MockMvc mockMvc;
 *
 *     @Test
 *     void createsAnOrder(RecordedTraces causeline) throws Exception {
 *         mockMvc.perform(post("/api/orders").contentType("application/json").content("{\"item\":\"book\"}"))
 *                 .andExpect(status().isCreated());
 *
 *         causeline.trace("POST /api/orders")
 *                 .hasNoFailedSpans()
 *                 .hasQueryCountAtMost(2)
 *                 .hasNoRepeatedQueries()
 *                 .hasSpans("OrderController.createOrder", "OrderService.createOrder");
 *     }
 * }
 * }
 *
 * <p>Turns Causeline on for the test's application context ({@code causeline.enabled=true}). Tests
 * in one class must not run in parallel: a trace can't be told apart from a neighbour's.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(CauselineExtension.class)
@TestPropertySource(properties = "causeline.enabled=true")
public @interface CauselineTest {
}
