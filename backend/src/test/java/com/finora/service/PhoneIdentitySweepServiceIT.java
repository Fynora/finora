package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.testsupport.FakePhoneVerificationProvider;
import com.finora.testsupport.TestPhoneVerificationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep over the real users table, with the fake provider standing in for Firebase. Proves the
 * live-number query: a number on an active, suspended or pending-deletion account is kept; a number
 * on a deleted account, or on none, is removed. Numbers are synthetic and random per test, because
 * the users table is shared by every test in the context.
 */
@Import(TestPhoneVerificationConfig.class)
class PhoneIdentitySweepServiceIT extends AbstractIntegrationTest {

    @Autowired private PhoneIdentitySweepService sweepService;
    @Autowired private PhoneVerificationProvider phoneVerificationProvider;
    @Autowired private UserRepository userRepository;

    private static final Instant TWO_DAYS_AGO = Instant.now().minus(Duration.ofDays(2));

    private FakePhoneVerificationProvider fake() {
        return (FakePhoneVerificationProvider) phoneVerificationProvider;
    }

    private static String randomNumber() {
        return "+9198" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
    }

    private String accountWith(String status) {
        String phone = randomNumber();
        User user = new User();
        user.setEmail("phone-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Phone Sweep IT User");
        user.setPhoneNumber(phone);
        user.setStatus(status);
        userRepository.save(user);
        return phone;
    }

    @Test
    void keepsNumbersOnAccountsNotDeleted_andRemovesTheRest() {
        String active = accountWith(User.STATUS_ACTIVE);
        String suspended = accountWith(User.STATUS_SUSPENDED);
        String pendingDeletion = accountWith(User.STATUS_PENDING_DELETION);
        String deleted = accountWith(User.STATUS_DELETED);
        String onNoAccount = randomNumber();
        for (String number : new String[] {active, suspended, pendingDeletion, deleted, onNoAccount}) {
            fake().addIdentity(number, TWO_DAYS_AGO);
        }

        sweepService.sweep(Instant.now());

        assertThat(fake().deletedIdentities())
                .contains(deleted, onNoAccount)
                .doesNotContain(active, suspended, pendingDeletion);
    }

    @Test
    void keepsARecordVerifiedWithinTheLastDayEvenWithNoAccount() {
        String inProgress = randomNumber();
        fake().addIdentity(inProgress, Instant.now().minus(Duration.ofHours(1)));

        sweepService.sweep(Instant.now());

        assertThat(fake().deletedIdentities()).doesNotContain(inProgress);
    }
}
