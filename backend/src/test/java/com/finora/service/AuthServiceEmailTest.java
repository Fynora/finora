package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.ForgotPasswordRequest;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Locks in forgotPassword()'s email-vs-devlink branching: with a configured EmailProvider, the
 * real email gets sent and the link is NOT exposed in the API response (it would be a needless
 * leak once real delivery exists); with no provider configured, the dev-convenience fallback
 * (returning the link directly) still works exactly as before.
 */
class AuthServiceEmailTest {

    private UserRepository userRepository;
    private PasswordResetTokenRepository resetTokenRepository;
    private EmailProvider emailProvider;
    private EmailProperties emailProperties;
    private AuditService auditService;
    private com.finora.config.RequestMetadata requestMetadata;
    private AccountRequestThrottle accountRequestThrottle;
    private AuthService authService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        emailProvider = mock(EmailProvider.class);
        emailProperties = new EmailProperties();
        emailProperties.setAppBaseUrl("http://localhost:5173");
        auditService = mock(AuditService.class);
        // Mirrors the real addTo contract (mutate and return) and supplies the labels a real request
        // would, so the audit rows below can be checked for who asked and from where.
        requestMetadata = mock(com.finora.config.RequestMetadata.class);
        when(requestMetadata.addTo(any())).thenAnswer(inv -> {
            java.util.Map<String, Object> metadata = inv.getArgument(0);
            metadata.put("ip", "203.0.113.9");
            metadata.put("device", "Mac");
            return metadata;
        });

        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("test@example.com");
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("test@example.com", "USER")).thenReturn(Optional.of(user));

        accountRequestThrottle = mock(AccountRequestThrottle.class);
        authService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, mock(RefreshTokenService.class), emailProvider, emailProperties,
                mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class),
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                requestMetadata,
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                mock(com.finora.service.MerchantSeedService.class),
                // SEC-07: same-thread executor -- runs the dispatched email/audit work
                // synchronously so assertions against it don't race a real background thread.
                Runnable::run,
                // SEC-03: no MFA gate interference for tests unrelated to it -- an
                // unstubbed mock's isEnabled() returns false by default.
                mock(AdminMfaService.class),
                mock(com.finora.repository.EmailLoginOtpRepository.class),
                mock(com.finora.service.SignupAttributionService.class),
                accountRequestThrottle
        );
    }

    @Test
    void forgotPassword_sendsRealEmail_andOmitsLinkFromResponse_whenEmailProviderConfigured() {
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.sendPasswordResetEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        verify(emailProvider).sendPasswordResetEmail(eq("test@example.com"), contains("/reset-password?token="));
        assertThat(response.devResetLink()).isNull();
        verify(auditService).recordEvenOnRollback(eq(userId), eq("EMAIL_SENT"), eq("User"), eq(userId),
                argThat(metadata -> "password_reset".equals(metadata.get("type")) && Boolean.TRUE.equals(metadata.get("success"))));
    }

    @Test
    void forgotPassword_recordsWhoAskedAndFromWhere_forAnExistingAccount() {
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.sendPasswordResetEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        // The request stores no IP of its own, so this row is the only trace of who asked.
        verify(auditService).recordEvenOnRollback(eq(userId), eq("PASSWORD_RESET_REQUESTED"), eq("User"), eq(userId),
                argThat(metadata -> "203.0.113.9".equals(metadata.get("ip")) && "Mac".equals(metadata.get("device"))));
    }

    /** SEC-07: the known-account path must not do more synchronous work than the unknown one needs to
     *  hide, and an inline audit insert is exactly that. The row is written after commit instead. */
    @Test
    void forgotPassword_doesNotWriteTheRequestAuditInline() {
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.sendPasswordResetEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        verify(auditService, org.mockito.Mockito.never()).record(any(), eq("PASSWORD_RESET_REQUESTED"), any(), any(), any());
    }

    private com.finora.exception.ApiException refusedForgotPassword(String email) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> authService.forgotPassword(new ForgotPasswordRequest(email, "USER"), null),
                com.finora.exception.ApiException.class);
    }

    /** Over the ceiling, nothing is issued and nothing is sent, and the owner is told so: the clients
     *  print a fixed "a reset link has been sent" for any success, so answering 200 would be a lie. */
    @Test
    void forgotPassword_overThePerAccountCeiling_issuesNothingAndSaysSoWithTheWait() {
        when(emailProvider.isConfigured()).thenReturn(true);
        when(accountRequestThrottle.resetRequestsExceeded("USER", "test@example.com")).thenReturn(true);
        when(accountRequestThrottle.resetWindowSeconds()).thenReturn(900L);

        var refused = refusedForgotPassword("test@example.com");

        assertThat(refused).isNotNull();
        assertThat(refused.getStatus()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getCode()).isEqualTo(com.finora.exception.ErrorCode.AUTH_RESET_REQUEST_LIMIT);
        assertThat(refused.getMessage()).contains("try again in about 15 minutes").contains("spam");
        assertThat(refused.getDetails()).containsEntry("retryAfterSeconds", 900L);
        verify(resetTokenRepository, org.mockito.Mockito.never()).save(any());
        verify(emailProvider, org.mockito.Mockito.never()).sendPasswordResetEmail(any(), any());
    }

    /** The refusal depends only on the count for the typed address, so a known and an unknown
     *  address are refused with exactly the same words. */
    @Test
    void forgotPassword_refusesAKnownAndAnUnknownAddressIdentically() {
        when(accountRequestThrottle.resetRequestsExceeded(eq("USER"), any())).thenReturn(true);
        when(accountRequestThrottle.resetWindowSeconds()).thenReturn(900L);

        var known = refusedForgotPassword("test@example.com");
        var unknown = refusedForgotPassword("nobody@example.com");

        assertThat(known.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(known.getCode()).isEqualTo(unknown.getCode());
        assertThat(known.getStatus()).isEqualTo(unknown.getStatus());
        assertThat(known.getMessage()).as("the typed address is never echoed back").doesNotContain("example.com");
    }

    /** The wait is rounded UP to whole minutes, so nobody is told to retry before the window ends. */
    @Test
    void forgotPassword_theWaitInTheMessageIsRoundedUpToWholeMinutes() {
        when(accountRequestThrottle.resetRequestsExceeded(eq("USER"), any())).thenReturn(true);

        when(accountRequestThrottle.resetWindowSeconds()).thenReturn(0L);
        assertThat(refusedForgotPassword("test@example.com").getMessage()).contains("try again in about a minute.");
        when(accountRequestThrottle.resetWindowSeconds()).thenReturn(60L);
        assertThat(refusedForgotPassword("test@example.com").getMessage()).contains("try again in about a minute.");
        when(accountRequestThrottle.resetWindowSeconds()).thenReturn(61L);
        assertThat(refusedForgotPassword("test@example.com").getMessage()).contains("try again in about 2 minutes.");
        when(accountRequestThrottle.resetWindowSeconds()).thenReturn(900L);
        assertThat(refusedForgotPassword("test@example.com").getMessage()).contains("try again in about 15 minutes.");
    }

    /** The count is taken on the typed address before any lookup, so a known and an unknown address
     *  do the same work at this step. */
    @Test
    void forgotPassword_countsAnUnknownAddressExactlyLikeAKnownOne() {
        authService.forgotPassword(new ForgotPasswordRequest("nobody@example.com", "USER"), null);
        authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        verify(accountRequestThrottle).resetRequestsExceeded("USER", "nobody@example.com");
        verify(accountRequestThrottle).resetRequestsExceeded("USER", "test@example.com");
    }

    /** The admin portal's reset is a different account from the same address's user one. */
    @Test
    void forgotPassword_countsTheAdminScopeSeparately() {
        authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "ADMIN"), null);

        verify(accountRequestThrottle).resetRequestsExceeded("ADMIN", "test@example.com");
    }

    @Test
    void forgotPassword_forAnUnknownAddress_recordsNothing_andAnswersTheSameWay() {
        when(emailProvider.isConfigured()).thenReturn(true);

        var response = authService.forgotPassword(new ForgotPasswordRequest("nobody@example.com", "USER"), null);

        // No user id to attach a row to, and the response must not differ from the known-account one.
        verify(auditService, org.mockito.Mockito.never()).recordEvenOnRollback(any(), eq("PASSWORD_RESET_REQUESTED"), any(), any(), any());
        verify(auditService, org.mockito.Mockito.never()).record(any(), eq("PASSWORD_RESET_REQUESTED"), any(), any(), any());
        assertThat(response.message()).isEqualTo("If an account exists for that email, we've sent a password reset link.");
        verify(emailProvider, org.mockito.Mockito.never()).sendPasswordResetEmail(any(), any());
    }

    /**
     * SEC-07 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). The other tests
     * in this file use a same-thread executor for determinism, which -- correctly -- makes them
     * unable to tell a dispatched call from an inline one. This test uses a capturing executor
     * instead, specifically to prove the property those others can't: forgotPassword() returns
     * without the send having happened yet, and the send only happens once the captured work is
     * actually run. Before this fix, the send was called inline, synchronously, before the method
     * returned -- indistinguishable from the "no matching account" branch only in payload, not in
     * how long either one took.
     */
    @Test
    void forgotPassword_dispatchesTheEmailSendRatherThanBlockingOnItInline() {
        java.util.concurrent.atomic.AtomicReference<Runnable> captured = new java.util.concurrent.atomic.AtomicReference<>();
        AuthService dispatchingAuthService = new AuthService(
                userRepository, mock(CategoryRepository.class), resetTokenRepository,
                mock(com.finora.repository.AccountReactivationTokenRepository.class),
                mock(com.finora.repository.EmailVerificationTokenRepository.class),
                mock(PasswordEncoder.class), mock(JwtService.class), mock(AuthenticationManager.class),
                auditService, mock(RefreshTokenService.class), emailProvider, emailProperties,
                mock(PhoneVerificationProvider.class), mock(PlatformSettingsService.class),
                mock(PasswordHistoryService.class), new IdentityLookup(userRepository),
                mock(com.finora.config.RequestMetadata.class),
                mock(com.finora.service.SubscriptionService.class),
                mock(com.finora.service.ReferralService.class),
                mock(com.finora.service.MerchantSeedService.class),
                captured::set, // records the work instead of running it
                mock(AdminMfaService.class),
                mock(com.finora.repository.EmailLoginOtpRepository.class),
                mock(com.finora.service.SignupAttributionService.class),
                mock(com.finora.service.AccountRequestThrottle.class)
        );
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.sendPasswordResetEmail(any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "test-message-id"));

        dispatchingAuthService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        // The method has already returned, and the send has NOT happened yet.
        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(captured.get()).as("the send must have been submitted to the executor").isNotNull();

        captured.get().run();

        verify(emailProvider).sendPasswordResetEmail(eq("test@example.com"), contains("/reset-password?token="));
    }

    @Test
    void forgotPassword_returnsLinkDirectly_andDoesNotSendEmail_whenNoProviderConfigured() {
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(response.devResetLink()).contains("/reset-password?token=");
    }

    /**
     * Bug fix: case-insensitive email uniqueness was never enforced before this session, so two
     * pre-existing accounts could differ only by case. findByEmailIgnoreCaseAndAccountScope(, "USER") throws
     * IncorrectResultSizeDataAccessException if it matches more than one row -- forgotPassword()
     * must fail closed to the same generic "if an account exists..." response every unresolvable
     * email gets, not bubble up as an opaque 500.
     */
    @Test
    void forgotPassword_whenEmailIgnoreCaseLookupIsAmbiguous_failsClosedInsteadOf500ing() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("jane@example.com", "USER"))
                .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1));

        var response = authService.forgotPassword(new ForgotPasswordRequest("jane@example.com", "USER"), null);

        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(response.devResetLink()).isNull();
    }

    @Test
    void forgotPassword_doesNotSendEmailOrLeakWhetherAccountExists_forUnknownEmail() {
        when(userRepository.findByEmailIgnoreCaseAndAccountScope("nobody@example.com", "USER")).thenReturn(Optional.empty());
        when(emailProvider.isConfigured()).thenReturn(true);

        var response = authService.forgotPassword(new ForgotPasswordRequest("nobody@example.com", "USER"), null);

        verify(emailProvider, never()).sendPasswordResetEmail(any(), any());
        assertThat(response.devResetLink()).isNull();
        assertThat(response.message()).isEqualTo("If an account exists for that email, we've sent a password reset link.");
    }

    /**
     * Bug fix: the user frontend and admin portal are two separate deployed apps at two
     * different origins, each with its own /reset-password page -- but there's no separate admin
     * auth service, so an admin's "Forgot Password" went through this exact same method. It used
     * to build every reset link from the single user-frontend base URL unconditionally, so an
     * admin got an email linking to the wrong app's reset-password page entirely.
     */
    @Test
    void forgotPassword_linksToTheAdminPortal_whenTheRequestOriginatedFromIt() {
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), "http://localhost:5174");

        assertThat(response.devResetLink()).startsWith("http://localhost:5174/reset-password?token=");
    }

    @Test
    void forgotPassword_stillLinksToTheUserFrontend_whenTheRequestOriginatedFromIt() {
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), "http://localhost:5173");

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }

    @Test
    void forgotPassword_fallsBackToTheUserFrontend_whenAdminAppBaseUrlWasNeverConfigured() {
        // ADMIN_APP_BASE_URL is optional -- an unconfigured deployment must keep behaving exactly
        // as it did before this fix, not start failing or linking somewhere blank.
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), "http://localhost:5174");

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }

    @Test
    void forgotPassword_fallsBackToTheUserFrontend_whenTheOriginHeaderIsMissing() {
        // A same-origin request (or any client that doesn't send Origin) has no way to signal
        // which app it is -- default to the user frontend rather than erroring.
        emailProperties.setAdminAppBaseUrl("http://localhost:5174");
        when(emailProvider.isConfigured()).thenReturn(false);

        var response = authService.forgotPassword(new ForgotPasswordRequest("test@example.com", "USER"), null);

        assertThat(response.devResetLink()).startsWith("http://localhost:5173/reset-password?token=");
    }
}
