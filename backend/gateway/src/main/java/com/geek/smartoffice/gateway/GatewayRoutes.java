package com.geek.smartoffice.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
    /**
     * Spring 创建此 Bean 时执行路由构建 Lambda，生成请求到来后使用的匹配与转发规则。
     * {@code @Value} 注入目标地址：本地默认 HTTP 直连，cloud 默认使用 lb://服务名进行负载均衡。
     * cloud 配置启用 Nacos 发现时，负载均衡组件据此选择服务实例；此方法不手动注册或查询 Nacos。
     */
    @Bean
    public RouteLocator smartOfficeRoutes(RouteLocatorBuilder builder,
                                         @Value("${smartoffice.routes.user-uri}") String userUri,
                                         @Value("${smartoffice.routes.booking-uri}") String bookingUri) {
        // 同时兼容原始路径与 /api 前缀；stripPrefix(1) 只删除最前面的一段路径。
        // 例如 /api/users/me 转成 /users/me，使下游 Controller 无需重复声明 /api 路径。
        return builder.routes()
                .route("users", route -> route.path("/users/**").uri(userUri))
                .route("api-users", route -> route.path("/api/users/**")
                        .filters(filters -> filters.stripPrefix(1)).uri(userUri))
                .route("bookings", route -> route.path("/rooms/**", "/orders/**", "/notifications/**", "/admin/messages/**")
                        .uri(bookingUri))
                .route("api-bookings", route -> route.path("/api/rooms/**", "/api/orders/**", "/api/notifications/**", "/api/admin/messages/**")
                        .filters(filters -> filters.stripPrefix(1)).uri(bookingUri))
                .build();
    }
}
