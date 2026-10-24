package com.finora.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The counting rules of {@link AccountRequestThrottle}, with Redis unreachable so that
 * {@code RateLimiter} counts in process: the same fixed window and the same limits as the Redis
 * script, and the realistic degraded mode. The real-Redis behaviour is in
 * {@code AccountRequestThrottleIT}.
 */
class AccountRequestThrottleTest {

    /** Every call on this template fails the way an unreachable Redis does. */
    private static StringRedisTemplate redisDown() {
        return mock(StringRedisTemplate.class, invocation -> {
            throw new QueryTimeoutException("redis is down");
        });
    }

    private AccountRequestThrottle throttle() {
        return new AccountRequestThrottle(redisDown(), 3, 900, 5, 600);
    }

    @Test
    void aResetRequestIsAllowedUpToTheCeilingAndRefusedAfterIt() {
        AccountRequestThrottle t = throttle();

        assertThat(t.resetRequestsExceeded("USER", "owner@example.com")).isFalse();
        assertThat(t.resetRequestsExceeded("USER", "owner@example.com")).isFalse();
        assertThat(t.resetRequestsExceeded("USER", "owner@example.com")).isFalse(); // the third is still allowed
        assertThat(t.resetRequestsExceeded("USER", "owner@example.com")).isTrue();  // the fourth is not
        assertThat(t.resetRequestsExceeded("USER", "owner@example.com")).isTrue();
    }

    @Test
    void theAddressIsNormalisedSoCaseAndSpacesCannotBuyExtraRequests() {
        AccountRequestThrottle t = throttle();

        t.resetRequestsExceeded("USER", "owner@example.com");
        t.resetRequestsExceeded("USER", "  Owner@Example.COM ");
        t.resetRequestsExceeded("USER", "OWNER@example.com");

        assertThat(t.resetRequestsExceeded("USER", "owner@example.com"))
                .as("three differently written spellings of one address used up its three requests")
                .isTrue();
    }

    @Test
    void oneAccountsRequestsDoNotUseUpAnothers() {
        AccountRequestThrottle t = throttle();
        for (int i = 0; i < 5; i++) t.resetRequestsExceeded("USER", "noisy@example.com");

        assertThat(t.resetRequestsExceeded("USER", "noisy@example.com")).isTrue();
        assertThat(t.resetRequestsExceeded("USER", "quiet@example.com")).isFalse();
    }

    @Test
    void theUserAndAdminAccountsOfOneAddressAreCountedSeparately() {
        AccountRequestThrottle t = throttle();
        for (int i = 0; i < 5; i++) t.resetRequestsExceeded("USER", "both@example.com");

        assertThat(t.resetRequestsExceeded("USER", "both@example.com")).isTrue();
        assertThat(t.resetRequestsExceeded("ADMIN", "both@example.com")).isFalse();
    }

    @Test
    void aMissingAddressIsCountedNotRejectedWithAnError() {
        AccountRequestThrottle t = throttle();

        assertThat(t.resetRequestsExceeded("USER", null)).isFalse();
    }

    @Test
    void aSignInCodeRequestIsAllowedUpToItsOwnCeiling() {
        AccountRequestThrottle t = throttle();
        UUID user = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertThat(t.codeRequestsExceeded(user)).as("request %d", i + 1).isFalse();
        }
        assertThat(t.codeRequestsExceeded(user)).isTrue();
        assertThat(t.codeRequestsExceeded(UUID.randomUUID())).as("another account").isFalse();
    }

    @Test
    void resetAndCodeRequestsAreCountedIndependently() {
        AccountRequestThrottle t = throttle();
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 10; i++) t.resetRequestsExceeded("USER", "owner@example.com");

        // An owner held off from reset can still ask for a sign-in code.
        assertThat(t.codeRequestsExceeded(user)).isFalse();
    }

    @Test
    void theWaitReportedToTheOwnerIsTheWholeCodeWindow() {
        assertThat(throttle().codeWindowSeconds()).isEqualTo(600);
    }
}
