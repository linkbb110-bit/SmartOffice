package com.geek.smartoffice.common.security;

import com.geek.smartoffice.common.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(new JwtProperties(
            "test-only-smartoffice-secret-key-with-at-least-32-bytes",
            Duration.ofHours(2)
    ));

    @Test
    void tokenRoundTripsIdentityAndRole() {
        String token = jwtService.createToken(7L, "alice", UserRole.ADMIN);

        AuthenticatedUser user = jwtService.parseToken(token);

        assertEquals(7L, user.userId());
        assertEquals("alice", user.username());
        assertEquals(UserRole.ADMIN, user.role());
    }
}
