package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.EmailVerificationToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.EmailVerificationTokenRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D-23. {@code AuthService.verifyEmail} -- confirms a {@code /verify-email?token=...} link.
 * Mirrors {@code AuthServiceReactivateTest}'s own setup and coverage shape exactly (same class of
 * token: hashed at rest, single-use, short-TTL), since this is the same pattern applied to a new
 * token type.
 */
class AuthServiceVerifyEmailTest {

    private UserRepository userRepository;
    private EmailVerificationTokenRepository emailVerificationTokenRepository;
    private AuditService auditService;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailVerificationTokenRepository = mock(EmailVerificationTokenRepository.class);
        auditService = mock(AuditService.class);

        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), mock(PasswordResetTokenRepository.class),
                mock(AccountReactivationTokenRepository.class),
                emailVerificationTokenRepository,
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, mock(RefreshTokenService.class), mock(EmailProvider.class),
                new EmailProperties(), mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class),
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                mock(com.finora.service.MerchantSeedService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class),
                mock(com.finora.repository.EmailLoginOtpRepository.class)
        );
    }

    private User unverifiedUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setEmailVerified(false);
        return u;
    }

    private EmailVerificationToken token(Instant expiresAt, Instant usedAt) {
        EmailVerificationToken t = new EmailVerificationToken();
        t.setUserId(userId);
        t.setTokenHash(TokenHasher.sha256("raw-token"));
        t.setExpiresAt(expiresAt);
        t.setUsedAt(usedAt);
        return t;
    }

    @Test
    void verifyEmail_withAValidToken_marksTheAccountVerified() {
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), null);
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(1);
        User u = unverifiedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.verifyEmail("raw-token");

        assertThat(u.isEmailVerified()).isTrue();
        assertThat(response.message()).isNotBlank();
        verify(auditService).record(userId, "EMAIL_VERIFIED", "User", userId);
    }

    @Test
    void verifyEmail_claimsTheTokenAtomically_soItCannotBeReplayed() {
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), null);
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(unverifiedUser()));

        authService.verifyEmail("raw-token");

        verify(emailVerificationTokenRepository).claimIfUnused(eq(t.getId()), any());
    }

    @Test
    void verifyEmail_withAnUnusedToken_forAnAccountVerifiedAnotherWay_succeedsWithoutASecondAuditRow() {
        // An email change verifies the account without burning links sent before it, so an
        // unused link can outlive the account becoming verified. Opening it has nothing to do.
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), null);
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(1);
        User u = unverifiedUser();
        u.setEmailVerified(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.verifyEmail("raw-token");

        assertThat(response.message()).isNotBlank();
        verify(emailVerificationTokenRepository).claimIfUnused(eq(t.getId()), any());
        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void verifyEmail_withAnExpiredUnusedToken_forAnAccountVerifiedAnotherWay_succeeds() {
        EmailVerificationToken t = token(Instant.now().minusSeconds(60), null);
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(1);
        User u = unverifiedUser();
        u.setEmailVerified(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.verifyEmail("raw-token");

        assertThat(response.message()).isNotBlank();
        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void verifyEmail_withAnExpiredToken_isRejectedAndDoesNotVerify() {
        EmailVerificationToken t = token(Instant.now().minusSeconds(60), null);
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(1);
        User u = unverifiedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            authService.verifyEmail("raw-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("expired");
            // There is no resend endpoint; the message names the one way a new link is sent.
            assertThat(e.getMessage()).contains("Sign in with Google or Apple again");
            assertThat(u.isEmailVerified()).isFalse();
            verify(userRepository, never()).save(any());
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for an expired token");
    }

    @Test
    void verifyEmail_withAnAlreadyUsedToken_forAnUnverifiedAccount_isRejected() {
        // Used, no newer link for the account, account still unverified -- spending it must not
        // verify anything.
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), Instant.now().minusSeconds(60));
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(0);
        when(emailVerificationTokenRepository.existsByUserIdAndCreatedAtAfter(userId, t.getCreatedAt())).thenReturn(false);
        User u = unverifiedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            authService.verifyEmail("raw-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("already been used");
            assertThat(u.isEmailVerified()).isFalse();
            verify(userRepository, never()).save(any());
            verify(auditService, never()).record(any(), any(), any(), any());
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for an already-used token on an unverified account");
    }

    @Test
    void verifyEmail_withALinkSupersededByANewerOne_forAnUnverifiedAccount_pointsAtTheNewerEmail() {
        // register() sent link A; a Google sign-in attempt then sent link B, and minting B burned A
        // without verifying anything. Opening A must say it was replaced, not that it was used.
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), Instant.now().minusSeconds(60));
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(0);
        when(emailVerificationTokenRepository.existsByUserIdAndCreatedAtAfter(userId, t.getCreatedAt())).thenReturn(true);
        User u = unverifiedUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        try {
            authService.verifyEmail("raw-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("replaced by a newer one");
            assertThat(e.getMessage()).doesNotContain("already been used");
            assertThat(u.isEmailVerified()).isFalse();
            verify(userRepository, never()).save(any());
            verify(auditService, never()).record(any(), any(), any(), any());
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for a superseded link on an unverified account");
    }

    @Test
    void verifyEmail_withALinkSupersededByANewerOne_forAVerifiedAccount_succeeds() {
        // The newer link already verified the account; the older email's link reports that.
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), Instant.now().minusSeconds(60));
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(0);
        when(emailVerificationTokenRepository.existsByUserIdAndCreatedAtAfter(userId, t.getCreatedAt())).thenReturn(true);
        User u = unverifiedUser();
        u.setEmailVerified(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.verifyEmail("raw-token");

        assertThat(response.message()).isNotBlank();
        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void verifyEmail_withAnAlreadyUsedToken_forAVerifiedAccount_succeedsWithoutWritingAnything() {
        // The link was spent by a double tap, by the browser and the app both opening it, or by an
        // email scanner fetching it first -- the account IS verified, so "Verification failed"
        // would be a lie. Success, but nothing is written and no second audit row is recorded.
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), Instant.now().minusSeconds(60));
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(0);
        User u = unverifiedUser();
        u.setEmailVerified(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.verifyEmail("raw-token");

        assertThat(response.message()).isNotBlank();
        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void verifyEmail_withAnAlreadyUsedExpiredToken_forAVerifiedAccount_stillSucceeds() {
        // Opened again days later from the same email: the account is verified, which is the only
        // thing the page reports. Expiry only guards a token that would still verify something.
        EmailVerificationToken t = token(Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600));
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(0);
        User u = unverifiedUser();
        u.setEmailVerified(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));

        var response = authService.verifyEmail("raw-token");

        assertThat(response.message()).isNotBlank();
        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyEmail_withAnAlreadyUsedToken_whoseAccountIsGone_isRejectedAsUsed() {
        EmailVerificationToken t = token(Instant.now().plusSeconds(600), Instant.now().minusSeconds(60));
        when(emailVerificationTokenRepository.findByTokenHash(TokenHasher.sha256("raw-token")))
                .thenReturn(Optional.of(t));
        when(emailVerificationTokenRepository.claimIfUnused(eq(t.getId()), any())).thenReturn(0);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        try {
            authService.verifyEmail("raw-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("already been used");
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for an already-used token with no account");
    }

    @Test
    void verifyEmail_withAnUnknownToken_isRejected() {
        when(emailVerificationTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        try {
            authService.verifyEmail("bogus-token");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("invalid");
            // Used links are found and answered by their own branch; a link that isn't found was
            // never used, so the message must not say it was.
            assertThat(e.getMessage()).doesNotContain("used");
            return;
        }
        throw new AssertionError("Expected verifyEmail() to throw for an unknown token");
    }
}
