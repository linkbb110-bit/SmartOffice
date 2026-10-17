package com.geek.smartoffice.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

@Component
public class ClientIdentityHeaderFilter implements GlobalFilter, Ordered {
    private static final Set<String> IDENTITY_HEADERS = Set.of("x-user-id", "x-user-role", "x-username");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            var forgedHeaders = headers.keySet().stream().filter(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return IDENTITY_HEADERS.contains(lower) || lower.startsWith("x-internal-");
            }).toList();
            forgedHeaders.forEach(headers::remove);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override public int getOrder() { return -100; }
}
