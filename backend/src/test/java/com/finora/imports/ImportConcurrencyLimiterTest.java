package com.finora.imports;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The failure paths of ImportConcurrencyLimiter that a real Redis cannot be made to produce on
 *  demand. The Redis-backed behaviour itself is covered by ImportConcurrencyLimiterIT. */
class ImportConcurrencyLimiterTest {

    /** Before the fix, an exception other than a DataAccessException escaped acquirePermit()
     *  holding the local permit it had just taken. Measured with maxConcurrent=1: the first call
     *  threw, and every call after it was refused as "busy" for the life of the process. Lettuce
     *  throws IllegalStateException, for one, once its connection factory has been stopped. */
    @Test
    @SuppressWarnings("unchecked")
    void anUnexpectedExceptionDuringAcquireGivesTheLocalPermitBack() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("LettuceConnectionFactory has been STOPPED"))
                .thenReturn("lease-2");
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redis);
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));

        assertThatThrownBy(() -> limiter.runGated(() -> "never runs"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(limiter.runGated(() -> "runs")).isEqualTo("runs");
    }

    /** Release runs in runGated()'s finally. Any exception from it would replace the finished
     *  work's result with an error; the lease is left to its safety TTL instead. */
    @Test
    @SuppressWarnings("unchecked")
    void aFailedReleaseDoesNotTurnFinishedWorkIntoAnError() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("granted");
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(zset.remove(any(), any())).thenThrow(new IllegalStateException("connection factory stopped"));
        when(redis.opsForZSet()).thenReturn(zset);
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redis);

        assertThat(limiter.runGated(() -> "imported")).isEqualTo("imported");
        // ...and the local permit was still returned.
        assertThat(limiter.runGated(() -> "imported again")).isEqualTo("imported again");
    }

    /** A zero TTL makes the acquire script prune the lease it has just added, so the count never
     *  includes it and the fleet-wide ceiling is never enforced. */
    @Test
    void aSafetyTtlBelowOneSecondIsRefusedAtStartup() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        assertThatThrownBy(() -> new ImportConcurrencyLimiter(6, 0, redis))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.import.concurrency-lease-ttl-seconds");
        assertThat(new ImportConcurrencyLimiter(6, 1, redis)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFleetWideCeilingBeingFullStillRefusesWithBusy() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redis);

        assertThatThrownBy(() -> limiter.runGated(() -> "never runs")).isInstanceOf(ApiException.class);
    }
}
