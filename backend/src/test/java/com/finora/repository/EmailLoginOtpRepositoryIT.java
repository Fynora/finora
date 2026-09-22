package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.EmailLoginOtp;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves uq_email_login_otps_unconsumed (V220, rescoped by V221) actually enforces "at most one
 *  unconsumed row per (email, accountScope)" -- the invariant AuthService.requestEmailLoginOtp()
 *  relies on the database to hold when its own read-then-write sequence loses a race (code review
 *  finding; see that method's own doc comment). A mocked EmailLoginOtpRepository can't prove a
 *  real constraint exists; this can. */
class EmailLoginOtpRepositoryIT extends AbstractIntegrationTest {

    @Autowired private EmailLoginOtpRepository emailLoginOtpRepository;
    @Autowired private UserRepository userRepository;

    private User saveUser(String email, String accountScope) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Email Login Otp Repository IT Test User");
        user.setAccountScope(accountScope);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private EmailLoginOtp unconsumedOtpFor(User user) {
        EmailLoginOtp otp = new EmailLoginOtp();
        otp.setUserId(user.getId());
        otp.setEmail(user.getEmail());
        otp.setAccountScope(user.getAccountScope());
        otp.setCodeHash("irrelevant-hash-for-this-test");
        otp.setExpiresAt(Instant.now().plusSeconds(300));
        return otp;
    }

    @Test
    @Transactional
    void savingASecondUnconsumedOtp_forTheSameEmailAndScope_violatesTheUniqueConstraint() {
        User user = saveUser("otp-repo-it-" + java.util.UUID.randomUUID() + "@example.com", User.SCOPE_USER);
        emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(user));

        assertThatThrownBy(() -> emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(user)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void savingASecondOtp_forTheSameEmailAndScope_afterTheFirstIsConsumed_succeeds() {
        User user = saveUser("otp-repo-it-" + java.util.UUID.randomUUID() + "@example.com", User.SCOPE_USER);
        EmailLoginOtp first = unconsumedOtpFor(user);
        first.setConsumedAt(Instant.now());
        emailLoginOtpRepository.saveAndFlush(first);

        // No exception -- a consumed row doesn't hold the unique constraint's slot.
        emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(user));
    }

    /** V52 lets the same email back a separate USER-scope and ADMIN-scope account. Without
     *  account_scope in the constraint/lookup, requesting a code for one would either collide with
     *  or be answered by a code actually issued for the other -- this proves both accounts can
     *  hold their own live, independent code under the identical email at the same time. */
    @Test
    @Transactional
    void unconsumedOtps_forTheSameEmail_inDifferentScopes_doNotCollide() {
        String sharedEmail = "otp-repo-it-dual-" + java.util.UUID.randomUUID() + "@example.com";
        User userAccount = saveUser(sharedEmail, User.SCOPE_USER);
        User adminAccount = saveUser(sharedEmail, User.SCOPE_ADMIN);

        emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(userAccount));
        // No exception -- the admin-scope account's own live code is a separate slot.
        emailLoginOtpRepository.saveAndFlush(unconsumedOtpFor(adminAccount));

        assertThat(emailLoginOtpRepository
                .findFirstByEmailAndAccountScopeAndConsumedAtIsNullOrderByCreatedAtDesc(sharedEmail, User.SCOPE_USER)
                .orElseThrow().getUserId()).isEqualTo(userAccount.getId());
        assertThat(emailLoginOtpRepository
                .findFirstByEmailAndAccountScopeAndConsumedAtIsNullOrderByCreatedAtDesc(sharedEmail, User.SCOPE_ADMIN)
                .orElseThrow().getUserId()).isEqualTo(adminAccount.getId());
    }
}
