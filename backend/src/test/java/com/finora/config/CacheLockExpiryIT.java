package com.finora.config;

import com.finora.AbstractIntegrationTest;
import com.finora.service.FeatureFlagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 2026-10-23 production outage: a {@code sync = true} cache load's Redis lock was left behind
 * with no expiry, and every later feature-flag check waited for it forever while holding a
 * database connection. {@code RecurringService.detectForUser} checks a flag first thing, so
 * {@code GET /recurring}, {@code /recurring/changed-amounts}, {@code /insights/look-at} and
 * {@code /insights/story} each hung until the pool of 10 was gone and sign-in and {@code /health}
 * failed with it.
 *
 * <p>These tests use the real Redis-backed cache manager and the real lock-waiting code in Spring
 * Data Redis; nothing here is mocked.
 */
class CacheLockExpiryIT extends AbstractIntegrationTest {

    private static final String FLAG = "RECURRING_DETECTION_ENABLED";

    @Autowired private CacheManager cacheManager;
    @Autowired private StringRedisTemplate redis;
    @Autowired private FeatureFlagService featureFlagService;
    @Autowired private CacheLockExpiry cacheLockExpiry;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        for (String name : cacheManager.getCacheNames()) {
            redis.delete(CacheLockExpiry.lockKey(name));
        }
    }

    @Test
    void theLockASyncLoadTakesExpiresOnItsOwn() throws Exception {
        Cache cache = cacheManager.getCache(CacheConfig.CUSTOM_BANKS_CACHE);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.submit(() -> cache.get("lock-ttl-" + System.nanoTime(), () -> {
            loading.countDown();
            release.await();
            return "value";
        }));
        try {
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

            Long ttlMillis = redis.getExpire(CacheLockExpiry.lockKey(CacheConfig.CUSTOM_BANKS_CACHE), TimeUnit.MILLISECONDS);

            // -1 is "no expiry": the setting that let one lost unlock hang production for good.
            assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(CacheConfig.CACHE_LOCK_TTL.toMillis());
        } finally {
            release.countDown();
        }
    }

    /** A load whose process died, or whose unlock never reached Redis, looks exactly like this
     *  to every other caller: the lock is there and nobody will remove it. */
    @Test
    void aCallerBehindAStuckLoadIsServedOnceTheLockExpires() throws Exception {
        Cache cache = cacheManager.getCache(CacheConfig.FEATURE_FLAGS_CACHE);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        pool.submit(() -> cache.get("stuck-" + System.nanoTime(), () -> {
            loading.countDown();
            never.await();
            return "never";
        }));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> featureFlagService.isEnabled(FLAG), pool);

        Boolean enabled = waiter.get(CacheConfig.CACHE_LOCK_TTL.toSeconds() + 5, TimeUnit.SECONDS);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(enabled).isNotNull();
        assertThat(waited).isLessThan(CacheConfig.CACHE_LOCK_TTL.plusSeconds(2));
    }

    /** Production may still hold a lock written by the build before this fix, with no expiry.
     *  Startup gives it one, so deploying the fix also clears what is already stuck. */
    @Test
    void aLockLeftWithNoExpiryIsGivenOneAndStopsBlocking() throws Exception {
        String lockKey = CacheLockExpiry.lockKey(CacheConfig.FEATURE_FLAGS_CACHE);
        cacheManager.getCache(CacheConfig.FEATURE_FLAGS_CACHE).evict(FLAG);
        redis.opsForValue().set(lockKey, "left-behind");
        assertThat(redis.getExpire(lockKey)).isEqualTo(-1L);

        // The planted key is the real lock: a flag check waits on it.
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> featureFlagService.isEnabled(FLAG), pool);
        assertThatThrownBy(() -> waiter.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

        assertThat(cacheLockExpiry.expireLocksLeftWithoutOne()).isEqualTo(1);

        assertThat(redis.getExpire(lockKey, TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(CacheConfig.CACHE_LOCK_TTL.toMillis());
        assertThat(waiter.get(CacheConfig.CACHE_LOCK_TTL.toSeconds() + 5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void aLockThatAlreadyExpiresIsLeftAlone() {
        String lockKey = CacheLockExpiry.lockKey(CacheConfig.CUSTOM_BANKS_CACHE);
        redis.opsForValue().set(lockKey, "in-flight", Duration.ofSeconds(3));

        assertThat(cacheLockExpiry.expireLocksLeftWithoutOne()).isZero();

        assertThat(redis.getExpire(lockKey, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(3000);
    }

    @Test
    void startupRepairDoesNotFailWhenRedisIsDown() {
        redis.opsForValue().set(CacheLockExpiry.lockKey(CacheConfig.CUSTOM_BANKS_CACHE), "left-behind");
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThat(cacheLockExpiry.expireLocksLeftWithoutOne()).isZero();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }
}
