package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.EmailLoginOtp;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves uq_email_login_otps_unconsumed (V220) actually enforces "at most one unconsumed row per
 *  email" -- the invariant AuthService.requestEmailLoginOtp() relies on the database to hold when
 *  its own read-then-write sequence loses a race (code review finding; see that method's own doc
 *  comment). A mocked EmailLoginOtpRepository can't prove a real constraint exists; this can. */
class EmailLoginOtpRepositoryIT extends AbstractIntegrationTest {

    @Autowired private EmailLoginOtpRepository emailLoginOtpRepository;
    @Autowired private UserRepository userRepository;

    private User saveUser() {
        User user = new User();
        user.setEmail("otp-repo-it-" + java.util.UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Email Login Otp Repository IT Test User");
        user.setAccountScope(User.SCOPE_USER);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private EmailLoginOtp unconsumedOtpFor(User user) {
        EmailLoginOtp otp = new EmailLoginOtp();
        otp.setUserId(user.getId());
        otp.setEmail(user.getEmail());
        otp.setCodeHash("irrelevant-hash-for-this-test");
        otp.setExpiresAt(Instant.now().plusSeconds(300));
        return otp;
    }

    @Test
    @Transactional
    void savingASecondUnconsumedOtp_forTheSameEmail_violatesTheUniqueConstraint() {
        User user = saveUser();
        emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(user));

        assertThatThrownBy(() -> emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(user)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void savingASecondOtp_forTheSameEmail_afterTheFirstIsConsumed_succeeds() {
        User user = saveUser();
        EmailLoginOtp first = unconsumedOtpFor(user);
        first.setConsumedAt(Instant.now());
        emailLoginOtpRepository.saveAndFlush(first);

        // No exception -- a consumed row doesn't hold the unique constraint's slot.
        emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(user));
    }
}
