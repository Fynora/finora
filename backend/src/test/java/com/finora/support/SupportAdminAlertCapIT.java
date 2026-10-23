package com.finora.support;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ticket and feedback submission are not rate limited, and each one emails every admin. This proves,
 * through the real property, executor and HTTP path, that a flood of submissions is capped at the
 * configured number of emails. Its own class because a low cap would make the other alert tests
 * depend on the order they run in.
 */
@TestPropertySource(properties = {
        "app.support-alert.max-per-window=3",
        "app.support-alert.window-minutes=60"
})
class SupportAdminAlertCapIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @MockitoBean private EmailProvider emailProvider;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("support-alert-cap-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Support Alert Cap IT");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        if (!"USER".equals(role)) {
            user.getRoles().add(roleRepository.findByName(role).orElseThrow());
        }
        return userRepository.save(user);
    }

    @Test
    void aFloodOfFeedback_emailsAnAdminNoMoreThanTheConfiguredCap() {
        User admin = createUser("ADMIN");
        User customer = createUser("USER");
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, customer));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = "{\"type\":\"GENERAL\",\"context\":\"OTHER\",\"message\":\"flood\"}";

        // Every submission succeeds; only the emails are capped.
        for (int i = 0; i < 6; i++) {
            ResponseEntity<String> response = restTemplate.exchange("/api/v1/feedback", HttpMethod.POST,
                    new HttpEntity<>(payload, headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        verify(emailProvider, timeout(10_000).atLeastOnce())
                .send(argThat(message -> message.to().equals(admin.getEmail())));
        // 6 submissions, a cap of 3: without the cap this admin would have 6 emails.
        verify(emailProvider, after(2000).atMost(3))
                .send(argThat(message -> message.to().equals(admin.getEmail())));
    }
}
