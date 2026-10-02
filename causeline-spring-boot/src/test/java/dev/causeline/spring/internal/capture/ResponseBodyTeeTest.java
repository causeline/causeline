// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ResponseBodyTeeTest {

    @Test
    void passesBytesThroughImmediatelyAndKeepsACopy() throws Exception {
        MockHttpServletResponse client = new MockHttpServletResponse();
        ResponseBodyTee tee = new ResponseBodyTee(client, 1024);

        tee.getOutputStream().write("{\"orderId\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(client.getContentAsString()).isEqualTo("{\"orderId\":1}");
        assertThat(new String(tee.copy(), StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":1}");
    }

    @Test
    void capturesWriterOutputInTheResponseCharset() throws Exception {
        MockHttpServletResponse client = new MockHttpServletResponse();
        client.setCharacterEncoding("UTF-8");
        ResponseBodyTee tee = new ResponseBodyTee(client, 1024);

        tee.getWriter().print("héllo");
        tee.finish();

        assertThat(client.getContentAsString()).isEqualTo("héllo");
        assertThat(new String(tee.copy(), StandardCharsets.UTF_8)).isEqualTo("héllo");
    }

    @Test
    void copiesOnlyUpToTheLimitButSendsEverything() throws Exception {
        MockHttpServletResponse client = new MockHttpServletResponse();
        ResponseBodyTee tee = new ResponseBodyTee(client, 4);

        tee.getOutputStream().write("0123456789".getBytes(StandardCharsets.UTF_8));

        assertThat(client.getContentAsString()).isEqualTo("0123456789");
        assertThat(new String(tee.copy(), StandardCharsets.UTF_8)).isEqualTo("0123");
        assertThat(tee.totalBytes()).isEqualTo(10);
    }
}
