package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.google.login.GoogleIdentity;
import com.finora.repository.EmailVerificationTokenRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * D-23 self-review fix, proven against a REAL Spring transaction rather than a mocked one.
 *
 * <p>{@code AuthService.loginWithGoogle}'s existing-but-unverified-email branch mints and saves an
 * {@link com.finora.entity.EmailVerificationToken}, registers an {@code AfterCommit} email send,
 * and then throws {@code ApiException}. {@code ApiException} is an unchecked
 * {@code RuntimeException}, so without {@code @Transactional(noRollbackFor = ApiException.class)}
 * on that method, Spring's default rule would silently roll back the very token row this whole
 * response promises the caller was created -- leaving the real account owner with no actual way
 * to verify and try again, despite the error message saying otherwise. A plain Mockito unit test
 * ({@code AuthServiceGoogleLoginTest}) mocks the repository, so {@code save()} being called looks
 * identical whether or not a real transaction would have kept it -- this is exactly the class of
 * bug that requires a real transactional boundary to catch, the same reason
 * {@code LoginExistenceOracleIT} exists for {@code login()}'s own {@code noRollbackFor}.
 */
class LoginWithGoogleUnverifiedEmailIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the freshly minted verification token survives the ApiException it's thrown alongside, not rolled back with it")
    void unverifiedAutoLinkAttempt_stillPersistsTheFreshVerificationToken() {
        String email = "google-unverified-" + UUID.randomUUID() + "@example.test";
        authService.register(new RegisterRequest(email, "SecurePass123", "Jane Doe", "+919876500001" /* synthetic-ok */, null));
        User registered = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow();
        assertThat(registered.isEmailVerified())
                .as("register() must leave a self-service account unverified until the link is clicked")
                .isFalse();

        // register()'s own link was sent moments ago, and a Google attempt inside the resend
        // cooldown deliberately mints nothing (see the next test). Age it past the cooldown so this
        // attempt takes the mint path whose rollback behaviour is under test here.
        ageVerificationLinks(registered.getId());
        long tokensBefore = emailVerificationTokenRepository.count();

        ApiException thrown = catchThrowableOfType(
                () -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")),
                ApiException.class);

        assertThat(thrown).as("Google sign-in must refuse to auto-link into an unverified account").isNotNull();
        assertThat(thrown.getMessage()).contains("verified");

        // The real assertion: a NEW token row exists after the throw, not just before it -- proving
        // the mint+save inside the branch that throws actually committed.
        assertThat(emailVerificationTokenRepository.count())
                .as("a fresh verification token must be durably persisted, not rolled back with the exception")
                .isEqualTo(tokensBefore + 1);
    }

    @Test
    @DisplayName("a Google attempt minutes after a link was sent mints nothing, so the link already in the inbox still verifies")
    void unverifiedAutoLinkAttemptInsideTheCooldown_leavesTheLiveLinkAlone() {
        String email = "google-unverified-cooldown-" + UUID.randomUUID() + "@example.test";
        String phone = "+919" + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
        authService.register(new RegisterRequest(email, "SecurePass123", "Jane Doe", phone, null));
        User registered = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow();

        long tokensBefore = emailVerificationTokenRepository.count();
        long liveBefore = countLiveLinks(registered.getId());
        assertThat(liveBefore).as("register() leaves exactly one live link").isEqualTo(1);

        for (int attempt = 0; attempt < 3; attempt++) {
            ApiException thrown = catchThrowableOfType(
                    () -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")),
                    ApiException.class);
            assertThat(thrown).as("Google sign-in must still refuse to auto-link into an unverified account").isNotNull();
            assertThat(thrown.getMessage()).contains("a few minutes ago");
        }

        assertThat(emailVerificationTokenRepository.count())
                .as("no new link is minted inside the cooldown")
                .isEqualTo(tokensBefore);
        assertThat(countLiveLinks(registered.getId()))
                .as("register()'s link is not burned by the repeated attempts")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the cooldown boundary, against the real query: a 9-minute-old live link blocks a resend, an 11-minute-old one does not")
    void resendCooldownBoundary() {
        String email = "google-unverified-boundary-" + UUID.randomUUID() + "@example.test";
        String phone = "+919" + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
        authService.register(new RegisterRequest(email, "SecurePass123", "Jane Doe", phone, null));
        UUID userId = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow().getId();

        shiftVerificationLinks(userId, "9 minutes");
        long before = emailVerificationTokenRepository.count();
        assertThat(catchThrowableOfType(() -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")),
                ApiException.class).getMessage()).contains("a few minutes ago");
        assertThat(emailVerificationTokenRepository.count()).isEqualTo(before);

        shiftVerificationLinks(userId, "2 minutes");  // now 11 minutes old
        assertThat(catchThrowableOfType(() -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")),
                ApiException.class).getMessage()).contains("sent a new verification link");
        assertThat(emailVerificationTokenRepository.count()).isEqualTo(before + 1);
        assertThat(countLiveLinks(userId)).as("the new link replaced the old one").isEqualTo(1);
    }

    private void ageVerificationLinks(UUID userId) {
        shiftVerificationLinks(userId, "1 hour");
    }

    private void shiftVerificationLinks(UUID userId, String interval) {
        jdbcTemplate.update(
                "UPDATE email_verification_tokens SET created_at = created_at - ?::interval WHERE user_id = ?",
                interval, userId);
    }

    private long countLiveLinks(UUID userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_verification_tokens WHERE user_id = ? AND used_at IS NULL AND expires_at > now()",
                Long.class, userId);
        return n == null ? 0 : n;
    }
}
