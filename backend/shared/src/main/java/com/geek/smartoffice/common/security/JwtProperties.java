package com.geek.smartoffice.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "smartoffice.jwt")
public record JwtProperties(String secret, Duration expiration) {
    /*
     * 这是 record 的紧凑构造器：参数来自配置绑定，校验完成后才由编译器赋给同名组件。
     * SecurityConfig 启用该配置类型，缺少密钥或使用非正有效期会在创建配置 Bean 时失败。
     * 此处只验证基本配置；密钥的 UTF-8 字节长度下限在 JwtService 创建时继续检查。
     */
    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT_SECRET must not be blank");
        }
        if (expiration == null || expiration.isZero() || expiration.isNegative()) {
            throw new IllegalArgumentException("JWT expiration must be positive");
        }
    }
}
