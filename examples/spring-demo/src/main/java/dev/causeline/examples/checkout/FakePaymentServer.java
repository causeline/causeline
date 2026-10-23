// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stands in for an external payment provider. It is deliberately slow by default and can be
 * switched to time out. It runs on its own port, outside the application, as a real third-party
 * service would: it reports no spans, and a load replay of the checkout measures the shop, not
 * the provider competing with it for the same Tomcat threads.
 */
@Component
class FakePaymentServer implements AutoCloseable {

    private final DemoSettings settings;
    private final int configuredPort;
    private HttpServer server;

    FakePaymentServer(DemoSettings settings, @Value("${demo.payment.port:8089}") int configuredPort) {
        this.settings = settings;
        this.configuredPort = configuredPort;
    }

    /** Started on first use, so the port is known once the shop calls it (tests use port 0). */
    synchronized int port() {
        if (server == null) {
            server = start();
        }
        return server.getAddress().getPort();
    }

    private HttpServer start() {
        HttpServer created;
        try {
            created = HttpServer.create(new InetSocketAddress("localhost", configuredPort), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the fake payment provider on port " + configuredPort, e);
        }
        created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        created.createContext("/fake-payment/charge", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(settings.paymentDelayMillis());
                boolean timeout = settings.failPayment();
                byte[] body = ("{\"status\":\"" + (timeout ? "TIMEOUT" : "APPROVED") + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(timeout ? 504 : 200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        created.start();
        return created;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
