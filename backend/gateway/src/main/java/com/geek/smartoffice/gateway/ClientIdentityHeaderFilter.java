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

    /**
     * 对进入路由过滤链的请求清理可伪造身份头，真实身份仍由下游校验 Authorization 中的 JWT。
     * 请求对象按只读方式使用，mutate()/build() 构造修改后的请求，再通过新的 exchange 传给下一环。
     * 返回的 {@code Mono<Void>} 表示后续异步处理的完成信号；由框架驱动响应，无需在这里阻塞等待。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            var forgedHeaders = headers.keySet().stream().filter(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return IDENTITY_HEADERS.contains(lower) || lower.startsWith("x-internal-");
            }).toList();
            // stream 先筛选并收集头名，再从原请求头中删除，避免遍历同一集合时修改它。
            // headers::remove 是方法引用，等价于对每个头名调用 headers.remove(name)。
            forgedHeaders.forEach(headers::remove);
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    // Ordered 的值越小越早进入过滤链，让身份头清理在较早阶段执行。
    @Override public int getOrder() { return -100; }
}
