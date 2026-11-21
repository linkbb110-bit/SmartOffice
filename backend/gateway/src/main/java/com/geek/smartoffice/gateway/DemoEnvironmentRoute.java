package com.geek.smartoffice.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;

/** 本地演示初始化的只读诊断入口，与预约接口使用相同上游，认证仍由预约服务负责。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "smartoffice.demo.enabled", havingValue = "true")
public class DemoEnvironmentRoute {
    /**
     * 只代理指定的 GET 路径，不开放任意 Actuator 地址。
     *
     * @param builder 路由构建器
     * @param bookingUri 预约服务地址，与业务路由共用配置
     * @return 演示身份检查路由
     */
    @Bean
    public RouteLocator demoIdentityRoutes(RouteLocatorBuilder builder,
                                          @Value("${smartoffice.routes.booking-uri}") String bookingUri) {
        return builder.routes()
                .route("local-demo-environment", route -> route
                        .path("/demo/environment", "/api/demo/environment").and().method(HttpMethod.GET)
                        .filters(filters -> filters.setPath("/actuator/info"))
                        .uri(bookingUri))
                .build();
    }
}
