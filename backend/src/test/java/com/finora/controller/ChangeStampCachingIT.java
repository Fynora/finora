package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole feature is "ask again and get a different answer". A response an intermediary or the
 * phone's own HTTP stack is allowed to cache would silently freeze it, so the stamp must say not to.
 */
class ChangeStampCachingIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @Test
    void theStampIsNeverCacheable() {
        User u = new User();
        u.setEmail("change-stamp-cache-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("not-used");
        u.setFullName("Cache Probe");
        u.setPhoneVerified(true);
        u = userRepository.save(u);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, u));
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/changes/stamp", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
}
