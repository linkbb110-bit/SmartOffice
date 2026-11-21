package com.geek.smartoffice.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=local", "smartoffice.demo.enabled=true",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"
})
class DemoEnvironmentRouteTest {
    private static final AtomicReference<String> path = new AtomicReference<>();
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final HttpServer booking = start();
    @Autowired private WebTestClient web;

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{\"smartofficeDemo\":{\"database\":\"so_ui001_demo_20261121\",\"mode\":\"demo\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("smartoffice.routes.booking-uri", () -> "http://127.0.0.1:" + booking.getAddress().getPort());
    }

    @AfterAll
    static void stop() { booking.stop(0); }

    @Test
    void diagnosticUsesTheBookingUpstreamAndRetainsAuthentication() {
        for (String route : new String[]{"/demo/environment", "/api/demo/environment"}) {
            web.get().uri(route).header("Authorization", "Bearer forwarded")
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.smartofficeDemo.database").isEqualTo("so_ui001_demo_20261121");
            assertEquals("/actuator/info", path.get());
            assertEquals("Bearer forwarded", authorization.get());
        }
    }

    @Test
    void doesNotForwardOtherManagementPathsOrWriteMethods() {
        path.set(null);
        web.get().uri("/api/actuator/env").exchange().expectStatus().isNotFound();
        web.post().uri("/api/demo/environment").exchange().expectStatus().isNotFound();
        assertNull(path.get());
    }
}
