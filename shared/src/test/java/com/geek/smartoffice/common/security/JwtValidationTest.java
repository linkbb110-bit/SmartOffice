package com.geek.smartoffice.common.security;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtValidationTest {
    private static final String SECRET = "test-only-smartoffice-secret-key-with-at-least-32-bytes";
    private final JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofHours(2)));

    private JwtBuilder claims() {
        return Jwts.builder().subject("alice").claim("uid", 7L).claim("role", "USER")
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void rejectsMissingUserId() {
        assertThrows(JwtException.class, () -> service.parseToken(claims().claim("uid", null).compact()));
    }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void rejectsNonPositiveUserId(long id) {
        assertThrows(JwtException.class, () -> service.parseToken(claims().claim("uid", id).compact()));
    }

    @Test void rejectsFractionalUserId() {
        assertThrows(JwtException.class, () -> service.parseToken(claims().claim("uid", 1.5).compact()));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankSubject(String subject) {
        assertThrows(JwtException.class, () -> service.parseToken(claims().subject(subject).compact()));
    }

    @Test void rejectsMissingRole() {
        assertThrows(JwtException.class, () -> service.parseToken(claims().claim("role", null).compact()));
    }

    @Test void rejectsUnknownRole() {
        assertThrows(JwtException.class, () -> service.parseToken(claims().claim("role", "SUPERUSER").compact()));
    }

    @Test void rejectsExpiredToken() {
        assertThrows(JwtException.class, () -> service.parseToken(claims()
                .expiration(Date.from(Instant.now().minusSeconds(60))).compact()));
    }

    @Test void rejectsTokenSignedWithAnotherSecret() {
        String other = "another-test-only-secret-with-at-least-32-bytes";
        assertThrows(JwtException.class, () -> service.parseToken(claims()
                .signWith(Keys.hmacShaKeyFor(other.getBytes(StandardCharsets.UTF_8))).compact()));
    }

    @Test void rejectsMissingExpiration() {
        assertThrows(JwtException.class, () -> service.parseToken(claims().expiration(null).compact()));
    }

    @ParameterizedTest @ValueSource(strings = {"", " "})
    void rejectsBlankSecret(String secret) {
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(secret, Duration.ofHours(2)));
    }

    @Test void rejectsMissingSecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(null, Duration.ofHours(2)));
    }

    @Test void rejectsZeroNegativeOrMissingExpiration() {
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(SECRET, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(SECRET, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new JwtProperties(SECRET, null));
    }
}
