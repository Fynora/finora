package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.EmailLoginOtpRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit test mocks the repository, so it can't show what {@code deleteByCreatedAtBefore}
 * actually needs at runtime. This runs the real {@code @Scheduled} entry point the way the
 * scheduler does -- with no transaction open -- against a real Postgres, with rows on both sides
 * of the 24-hour cutoff. Deliberately not {@code @Transactional}: an ambient test transaction
 * would hide a missing one in the service, the same way it hid the account-purge bug (see
 * AccountPurgeSweepServiceIT's non-transactional tests).
 */
class EmailLoginOtpRetentionSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private EmailLoginOtpRetentionSweepService sweep;
    @Autowired private EmailLoginOtpRepository emailLoginOtpRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void sweep_outsideAnyTransaction_deletesOnlyRowsOlderThanTwentyFourHours() {
        User user = new User();
        user.setEmail("otp-sweep-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("OTP Sweep Test User");
        UUID userId = userRepository.save(user).getId();

        // uq_email_login_otps_unconsumed allows one unconsumed code per user, so the old one is
        // consumed -- the realistic shape anyway, since a code older than a day has long been used
        // or superseded.
        UUID oldId = insertOtp(userId, user.getEmail(), Instant.now().minus(25, ChronoUnit.HOURS), true);
        UUID freshId = insertOtp(userId, user.getEmail(), Instant.now().minus(1, ChronoUnit.HOURS), false);

        sweep.sweep();

        assertThat(emailLoginOtpRepository.findById(oldId)).isEmpty();
        assertThat(emailLoginOtpRepository.findById(freshId)).isPresent();

        emailLoginOtpRepository.deleteAllById(java.util.List.of(freshId));
    }

    private UUID insertOtp(UUID userId, String email, Instant createdAt, boolean consumed) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO email_login_otps (id, user_id, email, account_scope, code_hash, "
                        + "expires_at, attempt_count, consumed_at, created_at) VALUES (?, ?, ?, 'USER', 'hash', ?, 0, ?, ?)",
                id, userId, email, Timestamp.from(createdAt.plus(5, ChronoUnit.MINUTES)),
                consumed ? Timestamp.from(createdAt.plus(1, ChronoUnit.MINUTES)) : null, Timestamp.from(createdAt));
        return id;
    }
}
