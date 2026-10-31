package com.geek.smartoffice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * 创建跨域规则 Bean，SecurityConfig 的 cors 默认配置会使用它处理浏览器预检及实际跨域请求。
     * allowedOrigins 从配置注入，按逗号拆分后统一去除两端空白，并丢弃空项再作为来源列表。
     * 允许凭据并不替代登录校验；允许的来源、方法、请求头仍须分别匹配，规则应用到所有路径。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${smartoffice.cors.allowed-origins}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        // String::trim 是方法引用，等价于逐项执行 value -> value.trim()；toList() 才收集处理后的来源。
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        // exposedHeaders 决定浏览器脚本可读哪些响应头；maxAge 是预检结果可缓存的秒数。
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
