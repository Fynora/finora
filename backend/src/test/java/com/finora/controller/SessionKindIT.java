package com.finora.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.support.ClientIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session kind reaches the real refresh_tokens.session_kind column (V310) from a real sign-in,
 * and a row written without it -- every session that existed before V310 -- reads as WEB.
 */
@AutoConfigureMockMvc
class SessionKindIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "SecurePass123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private User newUser() {
        User user = new User();
        user.setEmail("session-kind-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Session Kind");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private String signInAndReadKind(User user, String platform, String appLock) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "identifier", user.getEmail(), "password", PASSWORD, "scope", "USER")));
        if (platform != null) request.header(ClientIdentity.PLATFORM_HEADER, platform);
        if (appLock != null) request.header(ClientIdentity.APP_LOCK_HEADER, appLock);
        mockMvc.perform(request).andExpect(status().isOk());
        return jdbcTemplate.queryForObject(
                "SELECT session_kind FROM refresh_tokens WHERE user_id = ?", String.class, user.getId());
    }

    @Test
    void aSignInFromThePinLockAppIsAPhoneSession() throws Exception {
        assertThat(signInAndReadKind(newUser(), "MOBILE_IOS", "pin")).isEqualTo("MOBILE");
    }

    @Test
    void aSignInFromAnOlderAppBuildIsAWebSession() throws Exception {
        assertThat(signInAndReadKind(newUser(), "MOBILE_ANDROID", null)).isEqualTo("WEB");
    }

    @Test
    void aBrowserSignInIsAWebSession() throws Exception {
        assertThat(signInAndReadKind(newUser(), null, null)).isEqualTo("WEB");
    }

    @Test
    void aRowWrittenWithoutTheColumnReadsAsWeb() {
        User user = newUser();
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, session_id,
                    session_started_at, created_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), user.getId(), "hash-" + UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), UUID.randomUUID(),
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT session_kind FROM refresh_tokens WHERE user_id = ?", String.class, user.getId()))
                .isEqualTo("WEB");
    }
}
