package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.EmailOtpLoginRequest;
import com.finora.dto.AuthDtos.EmailOtpRequestRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.EmailLoginOtpRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Verifying an email OTP is a read-modify-write (check the attempt count, compare the code, bump
 * the counter or consume the row). Without a row lock, requests that race each read the same
 * attempt_count and write the same k + 1, so the 5-attempt cap under-counts; and two simultaneous
 * correct submissions both see the row unconsumed and both sign in from one single-use code.
 *
 * <p>These release all their threads at once from a latch against a real Postgres, because a mocked
 * repository has no concurrency to lose an update to. Deliberately NOT {@code @Transactional}.
 *
 * <p>The failing direction is probabilistic (a race has to actually happen); the passing direction
 * with the lock is deterministic. The lock's absence is measured, not assumed, in the commit that
 * added it.
 */
class EmailLoginOtpVerifyConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailLoginOtpRepository emailLoginOtpRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User verifiedUser() {
        User user = new User();
        user.setEmail("otp-race-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode("irrelevant-for-this-test"));
        user.setFullName("Otp Race Target");
        user.setPhoneVerified(true);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private String requestCode(User user) {
        String devCode = authService.requestEmailLoginOtp(new EmailOtpRequestRequest(user.getEmail(), null)).devCode();
        assertThat(devCode).as("no email provider is configured in the test profile, so the code is returned").isNotNull();
        return devCode;
    }

    /** Fires one login attempt per code, all released together; true = signed in, false = refused. */
    private List<Boolean> attemptTogether(User user, List<String> codes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(codes.size());
        try {
            CountDownLatch ready = new CountDownLatch(codes.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (String code : codes) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        authService.loginWithEmailOtp(new EmailOtpLoginRequest(user.getEmail(), code, null));
                        return true;
                    } catch (ApiException refused) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyConcurrentWrongGuesses_stillExhaustTheCode() throws Exception {
        User user = verifiedUser();
        String code = requestCode(user);
        String wrong = "000000".equals(code) ? "111111" : "000000";

        List<Boolean> outcomes = attemptTogether(user, Collections.nCopies(12, wrong));

        assertThat(outcomes).as("no wrong guess may sign in").doesNotContain(true);
        assertThat(emailLoginOtpRepository.findFirstByEmailAndAccountScopeAndConsumedAtIsNullOrderByCreatedAtDesc(
                user.getEmail(), User.SCOPE_USER))
                .as("12 guesses exceed the cap of 5, so the code must be spent; if the counter under-counted "
                        + "under contention it would still be live")
                .isEmpty();
        // And the code really is dead, not merely still counting.
        ApiException refused = catchThrowableOfType(
                () -> authService.loginWithEmailOtp(new EmailOtpLoginRequest(user.getEmail(), code, null)),
                ApiException.class);
        assertThat(refused).as("the correct code must be refused once the cap was exceeded").isNotNull();
    }

    @Test
    void aCorrectCodeSubmittedByManyRequestsAtOnce_signsInExactlyOnce() throws Exception {
        User user = verifiedUser();
        String code = requestCode(user);

        List<Boolean> outcomes = attemptTogether(user, Collections.nCopies(6, code));

        assertThat(outcomes.stream().filter(signedIn -> signedIn).count())
                .as("a single-use code must open exactly one session, however many requests race")
                .isEqualTo(1);
    }
}
