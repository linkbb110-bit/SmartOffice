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

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

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

    public AuthenticatedUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

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
