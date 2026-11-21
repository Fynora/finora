package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.EmailOtpLoginRequest;
import com.finora.dto.AuthDtos.EmailOtpRequestRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Audit F-07 (2026-11-20). A six-digit emailed code allowed five tries, and an account could be
 * sent five codes every ten minutes, with nothing counting the total: about 3,600 guesses a day
 * against one account, for as long as someone cared to keep going. After ten wrong codes in 24
 * hours, sign-in by emailed code is now paused for that account and its owner is told.
 *
 * <p>Against the real database: the count is read from the code rows themselves, and what matters
 * is that it survives the refusals that produce it. Not {@code @Transactional}, for the reason
 * EmailLoginOtpAttemptPersistenceIT gives.
 */
class SignInCodePauseIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "SecurePass123";

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User verifiedUser() {
        User user = new User();
        user.setEmail("code-pause-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Code Pause Target");
        user.setPhoneVerified(true);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    /** Asks for a code. First moves the account's earlier codes a minute into the past, so the
     *  30-second wait between codes does not stand in the way; they stay inside the 24 hours. */
    private String requestCode(User user) {
        jdbcTemplate.update("UPDATE email_login_otps SET created_at = created_at - interval '1 minute' "
                + "WHERE user_id = ?", user.getId());
        String code = authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null)).devCode();
        assertThat(code).as("no email provider in the test profile, so the code is returned").isNotNull();
        return code;
    }

    private String wrongCodeFor(String realCode) {
        return "000000".equals(realCode) ? "111111" : "000000";
    }

    private ApiException signInRefused(User user, String code) {
        return catchThrowableOfType(ApiException.class,
                () -> authService.loginWithEmailOtp(new EmailOtpLoginRequest(user.getEmail(), code, null)));
    }

    private ApiException codeRequestRefused(User user) {
        jdbcTemplate.update("UPDATE email_login_otps SET created_at = created_at - interval '1 minute' "
                + "WHERE user_id = ?", user.getId());
        return catchThrowableOfType(ApiException.class,
                () -> authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null)));
    }

    /** Enters {@code count} wrong codes, asking for a new code whenever the last one is used up. */
    private String enterWrongCodes(User user, int count) {
        String code = null;
        for (int i = 0; i < count; i++) {
            if (i % 5 == 0) code = requestCode(user);
            assertThat(signInRefused(user, wrongCodeFor(code))).as("wrong code %d", i + 1).isNotNull();
        }
        return code;
    }

    private List<Map<String, Object>> pauseAlerts(UUID userId) {
        return jdbcTemplate.queryForList("SELECT channel, category, title, message FROM notifications "
                + "WHERE user_id = ? AND type = 'SIGN_IN_CODE_PAUSED' ORDER BY channel", userId);
    }

    private void assertIsThePause(ApiException refusal) {
        assertThat(refusal).isNotNull();
        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refusal.getCode()).isEqualTo(ErrorCode.AUTH_OTP_RESEND_COOLDOWN);
        assertThat(refusal.getMessage()).contains("paused").contains("password");
    }

    @Test
    void afterTenWrongCodes_noNewCodeIsSent() {
        User user = verifiedUser();
        enterWrongCodes(user, 10);

        assertIsThePause(codeRequestRefused(user));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_login_otps WHERE user_id = ?", Long.class, user.getId()))
                .as("the refused request must not have issued a third code")
                .isEqualTo(2L);
    }

    /** The tenth wrong code can land on a code that still has tries left. The right code for it
     *  must not work either: the pause is on the account, not on one code. */
    @Test
    void afterTenWrongCodes_evenTheRightCodeIsRefused() {
        User user = verifiedUser();
        enterWrongCodes(user, 9);
        String live = requestCode(user);
        assertThat(signInRefused(user, wrongCodeFor(live))).isNotNull();

        assertIsThePause(signInRefused(user, live));
    }

    @Test
    void nineWrongCodes_doNotPause() {
        User user = verifiedUser();
        enterWrongCodes(user, 9);
        String code = requestCode(user);

        assertThat(authService.loginWithEmailOtp(new EmailOtpLoginRequest(user.getEmail(), code, null)).token())
                .as("nine wrong codes are under the limit, so the right code signs in")
                .isNotBlank();
        assertThat(pauseAlerts(user.getId())).isEmpty();
    }

    @Test
    void theOwnerIsToldOnce_whenThePauseBegins() {
        User user = verifiedUser();
        enterWrongCodes(user, 9);
        assertThat(pauseAlerts(user.getId())).as("not before the pause").isEmpty();
        String live = requestCode(user);
        signInRefused(user, wrongCodeFor(live));

        // Refused tries while paused are not counted and tell nobody again.
        signInRefused(user, wrongCodeFor(live));
        codeRequestRefused(user);

        List<Map<String, Object>> alerts = pauseAlerts(user.getId());
        assertThat(alerts).extracting(row -> row.get("channel")).containsExactly("EMAIL", "PUSH");
        assertThat(alerts).allSatisfy(row -> assertThat(row.get("category")).isEqualTo("SECURITY"));
        assertThat((String) alerts.get(0).get("message"))
                .contains("10")
                .contains("password")
                .as("every placeholder was filled in")
                .doesNotContain("{{");
    }

    /** The count is of the last 24 hours. Wrong codes older than that no longer hold the account. */
    @Test
    void thePauseEndsWhenTheWrongCodesAreADayOld() {
        User user = verifiedUser();
        enterWrongCodes(user, 10);
        assertIsThePause(codeRequestRefused(user));

        jdbcTemplate.update("UPDATE email_login_otps SET created_at = created_at - interval '25 hours' "
                + "WHERE user_id = ?", user.getId());

        String code = requestCode(user);
        assertThat(authService.loginWithEmailOtp(new EmailOtpLoginRequest(user.getEmail(), code, null)).token())
                .isNotBlank();
    }

    /**
     * As the oldest wrong codes pass 24 hours the count dips under the limit, and further wrong
     * codes take it over again on the same day. The second crossing asks for the same alert the
     * first did. That must not send a second one, and above all must not disturb the refusal it
     * happens inside: the wrong code is still counted and the account is paused again.
     */
    @Test
    void crossingTheLimitASecondTimeInADay_pausesAgainAndTellsNobodyTwice() {
        User user = verifiedUser();
        enterWrongCodes(user, 10);
        // The first five wrong codes are now more than a day old; the other five still count.
        jdbcTemplate.update("UPDATE email_login_otps SET created_at = created_at - interval '25 hours' "
                + "WHERE id = (SELECT id FROM email_login_otps WHERE user_id = ? ORDER BY created_at LIMIT 1)",
                user.getId());

        String code = enterWrongCodes(user, 5);

        assertIsThePause(signInRefused(user, code));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sum(attempt_count) FROM email_login_otps WHERE user_id = ? "
                        + "AND created_at > now() - interval '24 hours'", Long.class, user.getId()))
                .as("the wrong codes of the second run were all counted")
                .isEqualTo(10L);
        assertThat(pauseAlerts(user.getId())).extracting(row -> row.get("channel")).containsExactly("EMAIL", "PUSH");
    }

    /** Wrong codes entered against one account are that account's business only. */
    @Test
    void anotherAccountIsNotPaused() {
        User target = verifiedUser();
        User bystander = verifiedUser();
        enterWrongCodes(target, 10);

        String code = requestCode(bystander);

        assertThat(authService.loginWithEmailOtp(new EmailOtpLoginRequest(bystander.getEmail(), code, null)).token())
                .isNotBlank();
    }

    /** The pause is on codes. The owner's password is their way in while it lasts. */
    @Test
    void thePasswordStillWorksWhileCodesArePaused() {
        User user = verifiedUser();
        enterWrongCodes(user, 10);

        assertThat(authService.login(new com.finora.dto.AuthDtos.LoginRequest(user.getEmail(), PASSWORD, null)).token())
                .isNotBlank();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLockedUntil())
                .as("wrong codes do not lock the password")
                .isNull();
    }
}
