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
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.cloud.nacos.discovery.enabled=false","spring.cloud.discovery.enabled=false"})
class GatewayRoutingTest {
    static final AtomicReference<String> path=new AtomicReference<>();
    static final AtomicReference<String> spoof=new AtomicReference<>();
    static final AtomicReference<String> auth=new AtomicReference<>();
    static final AtomicReference<Map<String,String>> identities=new AtomicReference<>();
    static final AtomicReference<String> service=new AtomicReference<>();
    static final HttpServer usersUpstream=start("users");
    static final HttpServer bookingsUpstream=start("bookings");
    @Autowired WebTestClient web;
    static HttpServer start(String serviceName) {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                service.set(serviceName);
                path.set(exchange.getRequestURI().getPath());
                spoof.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                identities.set(exchange.getRequestHeaders().entrySet().stream()
                        .filter(entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("x-"))
                        .collect(Collectors.toMap(entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT), entry -> String.join(",", entry.getValue()))));
                byte[] body="{\"code\":200,\"message\":\"success\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) { output.write(body); }
            }); server.start(); return server;
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("smartoffice.routes.user-uri",()->"http://127.0.0.1:"+usersUpstream.getAddress().getPort());
        registry.add("smartoffice.routes.booking-uri",()->"http://127.0.0.1:"+bookingsUpstream.getAddress().getPort());
    }
    @AfterAll static void stop() { usersUpstream.stop(0); bookingsUpstream.stop(0); }
    @Test void apiPrefixRoutesUserAndStripsSpoofedIdentityHeaders() {
        web.get().uri("/api/users/me").header("Authorization","Bearer forwarded").header("X-User-Id","1")
                .exchange().expectStatus().isOk();
        assertEquals("/users/me",path.get());
        assertEquals("users",service.get());
        assertNull(spoof.get());
        assertEquals("Bearer forwarded",auth.get());
    }
    @Test void originalBookingPathsStillRoute() {
        web.get().uri("/orders/my").exchange().expectStatus().isOk();
        assertEquals("/orders/my",path.get());
        web.get().uri("/api/rooms/list").exchange().expectStatus().isOk();
        assertEquals("/rooms/list",path.get());
    }
    @Test void managementPathsAreNotRoutedToInternalActuator() {
        path.set(null);
        web.get().uri("/api/actuator/env").exchange().expectStatus().isNotFound();
        assertNull(path.get());
        web.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        assertNull(path.get());
        web.get().uri("/api/arbitrary").exchange().expectStatus().isNotFound();
        assertNull(path.get());
    }
    @Test void removesAllSpoofedIdentityHeadersButRetainsBearerAndOrdinaryRequestMetadata() {
        web.get().uri("/api/orders/my")
                .header("Authorization","Bearer retained")
                .header("x-user-id","forged")
                .header("X-User-Role","ADMIN")
                .header("X-Username","admin")
                .header("X-Internal-Token","forged")
                .header("x-internal-custom-identity","forged")
                .header("X-Request-Id","trace-123")
                .exchange().expectStatus().isOk();
        assertEquals("Bearer retained",auth.get());
        assertFalse(identities.get().containsKey("x-user-id"));
        assertFalse(identities.get().containsKey("x-user-role"));
        assertFalse(identities.get().containsKey("x-username"));
        assertFalse(identities.get().keySet().stream().anyMatch(key -> key.startsWith("x-internal-")));
        assertEquals("trace-123",identities.get().get("x-request-id"));
    }
    @Test void routesEveryAllowedSurfaceForBothOriginalAndApiPaths() {
        for (String route : new String[]{"/users/login","/rooms/1","/orders/my","/notifications/my","/admin/messages/outbox"}) {
            String expectedService = route.startsWith("/users/") ? "users" : "bookings";
            web.get().uri(route).exchange().expectStatus().isOk();
            assertEquals(route,path.get());
            assertEquals(expectedService,service.get());
            web.get().uri("/api"+route).exchange().expectStatus().isOk();
            assertEquals(route,path.get());
            assertEquals(expectedService,service.get());
        }
    }
    @Test void localHealthIsAvailableWithoutAuthenticationAndIsNotForwarded() {
        path.set(null);
        web.get().uri("/actuator/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        assertNull(path.get());
    }
}
