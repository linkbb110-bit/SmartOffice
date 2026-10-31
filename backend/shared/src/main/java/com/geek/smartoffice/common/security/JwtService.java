package com.geek.smartoffice.common.security;

import com.geek.smartoffice.common.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey key;

    /**
     * Spring 创建服务 Bean 时注入配置，并将文本密钥转换为后续签发、验签共用的 HMAC 密钥。
     * 使用 UTF-8 字节数判断最低长度，避免多字节字符的字符数与实际密钥字节数混淆。
     * 小于 32 字节直接拒绝初始化；不满足基本要求的配置不会等到处理登录请求时才暴露。
     */
    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 根据已通过登录校验的用户信息签发字符串令牌，非法身份参数会抛出 IllegalArgumentException。
     * subject 保存用户名，uid 和 role 是业务自定义载荷；签发时间和过期时间由当前时间及配置计算。
     * builder 逐项累积载荷，signWith 设置签名密钥，compact 才将其编码并签名为最终 JWT。
     * 签名用于检测内容是否被篡改，并不加密载荷；令牌中的角色也是签发时的快照，不会自行随数据库更新。
     */
    public String createToken(Long userId, String username, UserRole role) {
        if (userId == null || userId <= 0 || username == null || username.isBlank() || role == null) {
            throw new IllegalArgumentException("A valid user identity is required to create a JWT");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.expiration());
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /**
     * 解析已签名的 JWT，返回可用于 Security principal 的身份；无效签名、过期或载荷异常会拒绝解析。
     * verifyWith 指定验签密钥，parseSignedClaims 校验签名以及存在的有效期，成功后才读取 payload。
     * 库不替业务要求所有字段都存在，因此还需强制校验 uid、subject、role 和 expiration。
     * claims.get 的 Class 参数要求按指定类型取值，避免把任意字符串或小数用户 ID 当作合法身份。
     * 已知角色名转换为枚举；不在 UserRole 中的值转成 JwtException，交由过滤器统一按无效认证处理。
     */
    public AuthenticatedUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // 签名正确只说明载荷通过验签，不能替代业务身份完整性检查；此处也不回查用户表。
        Long userId = claims.get("uid", Long.class);
        String username = claims.getSubject();
        String roleClaim = claims.get("role", String.class);
        if (userId == null || userId <= 0 || username == null || username.isBlank()
                || roleClaim == null || claims.getExpiration() == null) {
            throw new JwtException("JWT is missing valid identity or expiration claims");
        }
        try {
            UserRole role = UserRole.valueOf(roleClaim);
            return new AuthenticatedUser(userId, username, role);
        } catch (IllegalArgumentException invalidRole) {
            throw new JwtException("JWT role is invalid", invalidRole);
        }
    }
}
