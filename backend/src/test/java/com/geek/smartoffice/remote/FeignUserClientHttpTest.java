package com.geek.smartoffice.remote;

import com.sun.net.httpserver.HttpServer;
import com.geek.smartoffice.common.enums.UserRole;
import com.geek.smartoffice.common.exception.BusinessException;
import com.geek.smartoffice.common.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=FeignUserClientHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"spring.cloud.nacos.discovery.enabled=false","spring.cloud.discovery.enabled=false","spring.cloud.sentinel.enabled=false","knife4j.enable=false"})
class FeignUserClientHttpTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,RedisAutoConfiguration.class,RedisRepositoriesAutoConfiguration.class})
    @EnableFeignClients(clients=UserClient.class)
    @Import(RemoteUserVerifier.class)
    static class Config {}
    static AtomicInteger status=new AtomicInteger(200);
    static AtomicReference<String> path=new AtomicReference<>(),authorization=new AtomicReference<>();
    static HttpServer server=start();
    @Autowired RemoteUserVerifier verifier;
    static HttpServer start() {
        try {
            var instance=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            instance.createContext("/",exchange->{
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body="{\"code\":200,\"message\":\"success\",\"data\":{\"userId\":1,\"username\":\"alice\",\"role\":\"USER\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status.get(),body.length);
                try(var out=exchange.getResponseBody()){out.write(body);}
            }); instance.start(); return instance;
        } catch(Exception ex){throw new IllegalStateException(ex);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("smartoffice.user-service.url",()->"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterAll static void stop(){server.stop(0);}
    @Test void realFeignUsesUsersMeAndForwardsJwt() {
        status.set(200);
        assertDoesNotThrow(()->verifier.verify(new AuthenticatedUser(1L,"alice",UserRole.USER),"Bearer contract-token"));
        assertEquals("/users/me",path.get());
        assertEquals("Bearer contract-token",authorization.get());
    }
    @Test void remoteUnauthorizedIs401AndRemoteServerFailureIs503() {
        var user=new AuthenticatedUser(1L,"alice",UserRole.USER);
        status.set(401);
        assertEquals(401,assertThrows(BusinessException.class,()->verifier.verify(user,"Bearer invalid")).getStatus().value());
        status.set(503);
        assertEquals(503,assertThrows(BusinessException.class,()->verifier.verify(user,"Bearer contract-token")).getStatus().value());
    }
}
