package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.google.login.GoogleIdentity;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The real path a user took: an account registered with mobile + password gets a verification
 * link (A) and stays unverified; each later "Sign in with Google" attempt on that email mints a
 * fresh link (B, then C), and minting burns every older unused link without verifying anything.
 * Opening an older email's link must say it was replaced -- not "already been used" -- and the
 * newest link must still verify. Links are captured from the real {@link EmailProvider} calls
 * (spied, not stubbed), so these are the exact tokens the emails would have carried.
 */
class EmailVerificationSupersededLinkIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    @MockitoSpyBean private EmailProvider emailProvider;

    @Test
    void anOlderEmailsLinkSaysItWasReplaced_theNewestVerifies_andOlderLinksThenReportVerified() {
        String email = "superseded-link-it-" + UUID.randomUUID() + "@example.test";
        // Unique per run: register() rejects a phone number another test already registered.
        String phone = "+919" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        authService.register(new RegisterRequest(email, "SecurePass123", "Jane Doe", phone, null));
        UUID userId = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow().getId();

        for (int attempt = 0; attempt < 2; attempt++) {
            ApiException refused = catchThrowableOfType(
                    () -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")),
                    ApiException.class);
            assertThat(refused).as("Google sign-in into an unverified account is refused").isNotNull();
        }

        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(emailProvider, times(3)).sendEmailVerificationEmail(eq(email), links.capture());
        List<String> tokens = links.getAllValues().stream()
                .map(link -> link.substring(link.indexOf("token=") + "token=".length()))
                .toList();
        String fromRegister = tokens.get(0);
        String fromFirstGoogleAttempt = tokens.get(1);
        String newest = tokens.get(2);

        for (String superseded : List.of(fromRegister, fromFirstGoogleAttempt)) {
            ApiException rejected = catchThrowableOfType(() -> authService.verifyEmail(superseded), ApiException.class);
            assertThat(rejected).as("an older email's link must not verify").isNotNull();
            assertThat(rejected.getMessage()).contains("replaced by a newer one");
            assertThat(rejected.getMessage()).doesNotContain("already been used");
        }
        assertThat(userRepository.findById(userId).orElseThrow().isEmailVerified()).isFalse();

        assertThat(authService.verifyEmail(newest).message()).isNotBlank();
        assertThat(userRepository.findById(userId).orElseThrow().isEmailVerified()).isTrue();

        assertThat(authService.verifyEmail(fromRegister).message())
                .as("once the account is verified, an older email's link reports that instead of failing")
                .isNotBlank();
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(userId).stream()
                .map(AuditLog::getAction)
                .filter("EMAIL_VERIFIED"::equals))
                .as("exactly one verification is recorded")
                .hasSize(1);
    }
}
