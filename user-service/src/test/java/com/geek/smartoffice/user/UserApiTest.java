package com.geek.smartoffice.user;

import com.geek.smartoffice.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwords;
    @BeforeEach void reset() { jdbc.update("DELETE FROM sys_user"); }
    @Test void registrationStoresBCryptAndCannotEscalateRole() throws Exception {
        mvc.perform(post("/users/register").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());
        assertEquals("USER",jdbc.queryForObject("SELECT role FROM sys_user WHERE username='alice'",String.class));
        String hash=jdbc.queryForObject("SELECT password FROM sys_user WHERE username='alice'",String.class);
        assertNotEquals("password123",hash);
        assertTrue(passwords.matches("password123",hash));
        mvc.perform(post("/users/register").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
    }
    @Test void loginAndMeUseDatabaseIdentityAndDeletedUserIsRejected() throws Exception {
        mvc.perform(post("/users/register").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        String body=mvc.perform(post("/users/login").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("data").asText();
        mvc.perform(get("/users/me").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("USER"));
        jdbc.update("UPDATE sys_user SET is_deleted=1 WHERE username='alice'");
        mvc.perform(get("/users/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void invalidTokenAnonymousAndInvalidParametersAreRejected() throws Exception {
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(post("/users/register").contentType("application/json")
                .content("{\"username\":\"a\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/login").contentType("application/json")
                .content("{\"username\":\"unknown\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void meReturnsDatabaseIdentityWithoutPasswordOrTokenIdentity() throws Exception {
        mvc.perform(post("/users/register").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isNumber());
        Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='alice'", Long.class);
        String token = jwt.createToken(id, "stale-name", com.geek.smartoffice.common.enums.UserRole.ADMIN);
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(id))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        jdbc.update("DELETE FROM sys_user WHERE id=?", id);
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings = {"ab", "bad-name", "with space", "姓名", "user!"})
    void rejectsInvalidRegistrationUsername(String username) throws Exception {
        String body = new ObjectMapper().writeValueAsString(java.util.Map.of("username", username, "password", "password123"));
        mvc.perform(post("/users/register").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class));
    }

    @Test void bcryptRejectsOver72Utf8BytesAndAcceptsExactBoundary() throws Exception {
        ObjectMapper json = new ObjectMapper();
        mvc.perform(post("/users/register").contentType("application/json").content(json.writeValueAsString(
                java.util.Map.of("username", "over_bytes", "password", "密".repeat(25)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/register").contentType("application/json").content(json.writeValueAsString(
                java.util.Map.of("username", "exact_bytes", "password", "密".repeat(24)))))
                .andExpect(status().isOk());
        String hash = jdbc.queryForObject("SELECT password FROM sys_user WHERE username='exact_bytes'", String.class);
        assertTrue(passwords.matches("密".repeat(24), hash));
        mvc.perform(post("/users/register").contentType("application/json").content(json.writeValueAsString(
                java.util.Map.of("username", "too_long", "password", "a".repeat(73)))))
                .andExpect(status().isBadRequest());
    }

    @Test void registrationProtectsDeletedUserUniqueName() throws Exception {
        jdbc.update("INSERT INTO sys_user(username,password,role,is_deleted) VALUES(?,?,?,1)", "alice", passwords.encode("password123"), "USER");
        mvc.perform(post("/users/register").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class));
    }

    @Test void malformedClaimsReturn401WithoutServerError() throws Exception {
        var key = Keys.hmacShaKeyFor("test-only-smartoffice-secret-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
        String missingRole = Jwts.builder().subject("alice").claim("uid", 1L)
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        String missingUid = Jwts.builder().subject("alice").claim("role", "USER")
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        for (String token : java.util.List.of(missingRole, missingUid)) {
            mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test void onlyPostLoginAndRegisterAndHealthArePublic() throws Exception {
        mvc.perform(get("/users/login")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/register")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }
}
