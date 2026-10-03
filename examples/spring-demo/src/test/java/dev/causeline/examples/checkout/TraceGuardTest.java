// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.causeline.test.CauselineTest;
import dev.causeline.test.RecordedTraces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The JUnit guard ({@code causeline-test}) on the demo checkout, written the way the UI's
 * "Copy as MockMvc test" writes it. No {@code dev} profile: {@code @CauselineTest} switches
 * Causeline on by itself. A real port is needed only because the demo's checkout calls its own
 * fake payment endpoint over HTTP.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "demo.payment.fast-delay-ms=0")
@AutoConfigureMockMvc
@CauselineTest
class TraceGuardTest {

    @Autowired
    MockMvc mockMvc;

    @AfterEach
    void resetDemoSwitches() throws Exception {
        settings(false);
    }

    @Test
    void aHealthyCheckoutPassesEveryCheck(RecordedTraces causeline) throws Exception {
        checkout();

        causeline.trace("POST /api/orders")
                .hasNoFailedSpans()
                .hasQueryCountAtMost(3)
                .hasNoRepeatedQueries()
                .hasSpans("OrderController.createOrder", "OrderService.createOrder", "OrderRepository.save")
                .hasSpansInOrder("POST /api/orders", "OrderController.createOrder", "OrderService.createOrder")
                .hasNoSpan("OrderRepository.findAll");
    }

    @Test
    void aCaughtAndLoggedExceptionFailsTheGuard(RecordedTraces causeline) throws Exception {
        settings(true);
        causeline.reset(); // the settings request is set-up, not what this test is about

        checkout(); // still answers 201: only the trace shows something went wrong

        causeline.trace().hasException("InventoryUnavailableException");
        assertThatThrownBy(() -> causeline.trace().hasNoFailedSpans())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("EXCEPTION InventoryUnavailableException")
                .hasMessageContaining("SERVICE OrderService.createOrder");
    }

    @Test
    void anExtraQueryFailsTheGuardAndTheMessageListsThem(RecordedTraces causeline) throws Exception {
        checkout();

        assertThatThrownBy(() -> causeline.trace("POST /api/orders").hasQueryCountAtMost(0))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected at most 0 SQL queries")
                .hasMessageContaining("INSERT orders");
    }

    @Test
    void aTestThatTracesNothingIsToldSo(RecordedTraces causeline) {
        assertThatThrownBy(() -> causeline.within(java.time.Duration.ofMillis(200)).trace())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No trace was recorded");
    }

    private void checkout() throws Exception {
        mockMvc.perform(post("/api/orders").contentType("application/json").content("{\"item\":\"book\",\"quantity\":1}"))
                .andExpect(status().isCreated());
    }

    private void settings(boolean flakyStock) throws Exception {
        mockMvc.perform(put("/api/demo/settings").contentType("application/json")
                        .content("{\"slowPayment\":false,\"failPayment\":false,\"flakyStock\":" + flakyStock + "}"))
                .andExpect(status().is2xxSuccessful());
    }
}
