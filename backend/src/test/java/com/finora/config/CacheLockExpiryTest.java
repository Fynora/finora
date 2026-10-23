package com.finora.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The scheduled path of {@link CacheLockExpiry}; the Redis behaviour itself is in CacheLockExpiryIT. */
class CacheLockExpiryTest {

    private final CacheManager cacheManager = mock(CacheManager.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CacheLockExpiry repair = new CacheLockExpiry(cacheManager, redis);

    @Test
    void theScheduledRepairGivesALockWithNoExpiryOne() {
        when(cacheManager.getCacheNames()).thenReturn(List.of("featureFlags", "customBanks"));
        when(redis.getExpire("featureFlags~lock")).thenReturn(-1L);
        when(redis.getExpire("customBanks~lock")).thenReturn(-2L);
        when(redis.expire("featureFlags~lock", CacheConfig.CACHE_LOCK_TTL)).thenReturn(true);

        repair.scheduledRepair();

        verify(redis).expire("featureFlags~lock", CacheConfig.CACHE_LOCK_TTL);
        verify(redis, never()).expire("customBanks~lock", CacheConfig.CACHE_LOCK_TTL);
    }

    @Test
    void theScheduledRepairDoesNothingWhenSwitchedOff() {
        ReflectionTestUtils.setField(repair, "scheduledRepairEnabled", false);

        repair.scheduledRepair();

        verifyNoInteractions(cacheManager, redis);
    }

    @Test
    void aRedisFailureStopsThePassWithoutThrowing() {
        when(cacheManager.getCacheNames()).thenReturn(List.of("featureFlags", "customBanks"));
        when(redis.getExpire(anyString())).thenThrow(new QueryTimeoutException("Redis command timed out"));

        assertThat(repair.expireLocksLeftWithoutOne()).isZero();

        verify(redis, times(1)).getExpire(anyString());
        verify(redis, never()).expire(anyString(), any(java.time.Duration.class));
    }
}
