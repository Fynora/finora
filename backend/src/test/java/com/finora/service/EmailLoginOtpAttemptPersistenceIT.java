package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.EmailOtpLoginRequest;
import com.finora.dto.AuthDtos.EmailOtpRequestRequest;
import com.finora.dto.AuthDtos.PhoneOtpLoginRequest;
import com.finora.entity.AuditLog;
import com.finora.entity.EmailLoginOtp;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.EmailLoginOtpRepository;
import com.finora.repository.UserRepository;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

/**
 * Proves that OTP login's WRITE-THEN-THROW paths actually persist their writes.
 *
 * <p>{@code ApiException} is a {@code RuntimeException}, so under a plain {@code @Transactional}
 * every write made before the rejection is thrown is rolled back with it. {@code login()} hit
 * exactly this (see its own doc comment: the failed-login counter never persisted, so lockout did
 * not work) and carries {@code noRollbackFor = ApiException.class} for it. The OTP login methods
 * write the same way -- {@code attemptCount++} on a wrong code, a LOGIN_FAILED audit row, consuming
 * the code on exhaustion or on a correct code that then meets a suspended account -- and the mocked
 * unit tests cannot see a rollback, because with a mocked repository no transaction exists to undo
 * anything.
 *
 * <p>Deliberately NOT {@code @Transactional}: a test-level transaction would wrap the service call,
 * absorb the rollback-only marking and let every read below see uncommitted changes, hiding the
 * exact defect this class exists to catch.
 */
class EmailLoginOtpAttemptPersistenceIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailLoginOtpRepository emailLoginOtpRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AccountReactivationTokenRepository reactivationTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    // Firebase itself is out of scope; what matters here is what happens AFTER it vouches for a number.
    @MockitoBean private PhoneVerificationProvider phoneVerificationProvider;

    private User verifiedUser() {
        User user = new User();
        user.setEmail("otp-persist-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("irrelevant-for-this-test"));
        user.setFullName("Otp Persistence Target");
        user.setPhoneVerified(true);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    /** The code the test environment hands back in the response, since no email provider is
     *  configured there (the same dev-convenience fallback forgotPassword uses). */
    private String requestCode(User user) {
        String devCode = authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null)).devCode();
        assertThat(devCode).as("no email provider is configured in the test profile, so the code is returned").isNotNull();
        return devCode;
    }

    private String wrongCodeFor(String realCode) {
        return "000000".equals(realCode) ? "111111" : "000000";
    }

    private Optional<EmailLoginOtp> liveOtp(User user) {
        return emailLoginOtpRepository.findFirstByEmailAndAccountScopeAndConsumedAtIsNullOrderByCreatedAtDesc(
                user.getEmail(), User.SCOPE_USER);
    }

    private ApiException failedLogin(User user, String code) {
        ApiException thrown = catchThrowableOfType(
                () -> authService.loginWithEmailOtp(new EmailOtpLoginRequest(user.getEmail(), code, null)),
                ApiException.class);
        assertThat(thrown).as("this login must be refused").isNotNull();
        return thrown;
    }

    @Test
    void aWrongCode_countsAsAnAttemptThatSurvivesTheRejection() {
        User user = verifiedUser();
        String code = requestCode(user);

        failedLogin(user, wrongCodeFor(code));

        assertThat(liveOtp(user)).isPresent();
        assertThat(liveOtp(user).orElseThrow().getAttemptCount())
                .as("the attempt counter must survive the 401 being thrown")
                .isEqualTo(1);
    }

    @Test
    void afterFiveWrongCodes_evenTheCorrectCodeIsRefused() {
        User user = verifiedUser();
        String code = requestCode(user);

        for (int i = 0; i < 5; i++) {
            failedLogin(user, wrongCodeFor(code));
        }

        // If the counter never persisted, the code would still be worth 5 more guesses forever
        // and this would sign in.
        failedLogin(user, code);
        assertThat(liveOtp(user)).as("an exhausted code must not stay live").isEmpty();
    }

    @Test
    void aWrongCode_leavesALoginFailedAuditRow() {
        User user = verifiedUser();
        String code = requestCode(user);

        failedLogin(user, wrongCodeFor(code));

        assertThat(auditLogRepository.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(AuditLog::getAction)
                .as("the failed attempt must be on the audit trail, not rolled back with the 401")
                .contains("LOGIN_FAILED");
    }

    /** The reactivation prompt hands the token back to the client, which then calls /reactivate with
     *  it. A token minted inside a transaction that is then rolled back would not exist by then, so
     *  the "Reactivate my account" button would fail for every deactivated user who signed in by
     *  code. */
    @Test
    void aDeactivatedAccount_getsAReactivationTokenThatActuallyExists() {
        User user = verifiedUser();
        String code = requestCode(user);
        user.setStatus(User.STATUS_DEACTIVATED);
        user.setDeactivatedAt(java.time.Instant.now());
        userRepository.save(user);

        ApiException thrown = failedLogin(user, code);

        Object rawToken = thrown.getDetails().get("reactivationToken");
        assertThat(rawToken).as("the deactivated-account response carries a reactivation token").isNotNull();
        assertThat(reactivationTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken.toString())))
                .as("and that token must have been persisted, not rolled back with the rejection")
                .isPresent();
    }

    /** A runtime-generated number, unique per call: the users table is shared by every IT and phone
     *  numbers are unique per scope. */
    private User userWithPhone(boolean phoneVerified) {
        User user = verifiedUser();
        user.setPhoneNumber(String.format("+9199%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)));
        user.setPhoneVerified(phoneVerified);
        return userRepository.save(user);
    }

    private ApiException failedPhoneLogin(User user) {
        when(phoneVerificationProvider.verifyAndGetPhoneNumber("firebase-token")).thenReturn(user.getPhoneNumber());
        ApiException thrown = catchThrowableOfType(
                () -> authService.loginWithPhoneOtp(new PhoneOtpLoginRequest("firebase-token", null)),
                ApiException.class);
        assertThat(thrown).as("this phone login must be refused").isNotNull();
        return thrown;
    }

    @Test
    void phone_aDeactivatedAccount_getsAReactivationTokenThatActuallyExists() {
        User user = userWithPhone(true);
        user.setStatus(User.STATUS_DEACTIVATED);
        user.setDeactivatedAt(java.time.Instant.now());
        userRepository.save(user);

        ApiException thrown = failedPhoneLogin(user);

        Object rawToken = thrown.getDetails().get("reactivationToken");
        assertThat(rawToken).isNotNull();
        assertThat(reactivationTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken.toString())))
                .as("the token handed back must exist, not be rolled back with the rejection")
                .isPresent();
    }

    @Test
    void phone_anUnverifiedPhone_isRefusedAndLeavesALoginFailedAuditRow() {
        User user = userWithPhone(false);

        failedPhoneLogin(user);

        assertThat(auditLogRepository.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .extracting(AuditLog::getAction)
                .contains("LOGIN_FAILED");
    }

    @Test
    void aCorrectCodeThatMeetsASuspendedAccount_isStillSpent() {
        User user = verifiedUser();
        String code = requestCode(user);
        user.setStatus(User.STATUS_SUSPENDED);
        userRepository.save(user);

        failedLogin(user, code);

        assertThat(liveOtp(user))
                .as("the code was correctly entered, so it is spent even though the sign-in was refused")
                .isEmpty();
    }
}
