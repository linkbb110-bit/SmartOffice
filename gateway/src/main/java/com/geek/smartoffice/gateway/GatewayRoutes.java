package com.geek.smartoffice.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
    @Bean
    public RouteLocator smartOfficeRoutes(RouteLocatorBuilder builder,
                                         @Value("${smartoffice.routes.user-uri}") String userUri,
                                         @Value("${smartoffice.routes.booking-uri}") String bookingUri) {
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
