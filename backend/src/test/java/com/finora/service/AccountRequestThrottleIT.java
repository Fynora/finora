package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.EmailOtpRequestRequest;
import com.finora.dto.AuthDtos.ForgotPasswordRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The per-account ceiling on reset and sign-in-code emails, through the real {@link AuthService}
 * with a real Redis and a real database: what a unit test with a mocked throttle cannot show is
 * that the count survives across separate requests and really stops the token and the code being
 * issued.
 *
 * <p>No email provider is configured in this environment, so an issued reset also returns its
 * link in the response ({@code devResetLink}); with a real provider the response is the same
 * generic message either way. The tests therefore compare the message, and count what was issued
 * in the database.
 */
class AccountRequestThrottleIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private User verifiedUser() {
        User user = new User();
        user.setEmail("throttle-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery-staple"));
        user.setFullName("Throttle IT User");
        user.setPhoneVerified(true);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private long resetTokensOf(UUID userId) {
        return jdbc.queryForObject("select count(*) from password_reset_tokens where user_id = ?", Long.class, userId);
    }

    /** What a caller of the reset endpoint gets back, as one comparable string: the message when it
     *  is accepted, or the status, code and message when it is refused. */
    private String outcomeOfReset(String email) {
        try {
            return "ACCEPTED " + authService.forgotPassword(new ForgotPasswordRequest(email, "USER"), null).message();
        } catch (ApiException e) {
            return "REFUSED " + e.getStatus() + " " + e.getCode() + " " + e.getMessage();
        }
    }

    @Test
    void onlyTheFirstThreeResetRequestsForAnAccountIssueAToken_andTheRestAreToldSo() {
        User user = verifiedUser();
        List<String> outcomes = new ArrayList<>();

        for (int i = 0; i < 6; i++) outcomes.add(outcomeOfReset(user.getEmail()));

        assertThat(resetTokensOf(user.getId()))
                .as("the ceiling is three per window, however many were asked for")
                .isEqualTo(3);
        assertThat(outcomes.subList(0, 3)).allSatisfy(o -> assertThat(o).startsWith("ACCEPTED"));
        assertThat(outcomes.subList(3, 6)).allSatisfy(o -> assertThat(o)
                .startsWith("REFUSED 429 TOO_MANY_REQUESTS")
                .contains("AUTH_RESET_REQUEST_LIMIT")
                .contains("try again in about 15 minutes"));
    }

    @Test
    void anUnknownAddressIsAnsweredTheSameAsAKnownOneAtEveryStep() {
        User known = verifiedUser();
        String unknown = "nobody-" + UUID.randomUUID() + "@example.com";
        List<String> knownOutcomes = new ArrayList<>();
        List<String> unknownOutcomes = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            knownOutcomes.add(outcomeOfReset(known.getEmail()));
            unknownOutcomes.add(outcomeOfReset(unknown));
        }

        assertThat(unknownOutcomes).as("accepted and refused alike, step for step").isEqualTo(knownOutcomes);
        assertThat(knownOutcomes.stream().filter(o -> o.startsWith("REFUSED")).count()).isEqualTo(3);
    }

    @Test
    void anotherAccountIsUnaffectedByOneThatHasHitItsCeiling() {
        User noisy = verifiedUser();
        User other = verifiedUser();
        for (int i = 0; i < 6; i++) outcomeOfReset(noisy.getEmail());

        assertThat(outcomeOfReset(other.getEmail())).startsWith("ACCEPTED");

        assertThat(resetTokensOf(other.getId())).isEqualTo(1);
    }

    @Test
    void onlyTheFirstFiveSignInCodeRequestsForAnAccountIssueACode_andTheNextIsRefusedWithTheWholeWindow() {
        User user = verifiedUser();
        int issued = 0;

        for (int i = 0; i < 5; i++) {
            authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null));
            issued++;
            // Step past the 30-second wait between codes, which is a different rule from this one.
            jdbc.update("update email_login_otps set created_at = created_at - interval '2 minutes' where user_id = ?",
                    user.getId());
        }
        ApiException refused = catchThrowableOfType(
                () -> authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null)),
                ApiException.class);

        assertThat(issued).isEqualTo(5);
        assertThat(refused).as("the sixth request is refused").isNotNull();
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getDetails()).containsEntry("retryAfterSeconds", 600L);
        assertThat(refused.getMessage()).contains("try again in about 10 minutes").contains("sign in with your password");
        assertThat(jdbc.queryForObject("select count(*) from email_login_otps where user_id = ?", Long.class, user.getId()))
                .as("the refused request issued no code")
                .isEqualTo(5);
    }

    @Test
    void aRequestTheShortWaitRefusesDoesNotUseUpTheCodeAllowance() {
        User user = verifiedUser();
        authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null));

        // Ten more taps straight away: every one is refused by the 30-second wait, none issues a code.
        for (int i = 0; i < 10; i++) {
            catchThrowableOfType(
                    () -> authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null)),
                    ApiException.class);
        }
        jdbc.update("update email_login_otps set created_at = created_at - interval '2 minutes' where user_id = ?", user.getId());

        // Only one code has really been issued, so four of the five allowed requests remain.
        for (int i = 0; i < 4; i++) {
            authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null));
            jdbc.update("update email_login_otps set created_at = created_at - interval '2 minutes' where user_id = ?", user.getId());
        }
        assertThat(jdbc.queryForObject("select count(*) from email_login_otps where user_id = ?", Long.class, user.getId()))
                .isEqualTo(5);
    }
}
