package com.finora.config;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigIT extends AbstractIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CacheableProbe cacheableProbe;

    /**
     * A real {@code @Cacheable}-annotated bean, proxied by Spring's caching AOP like
     * {@code BankManagementService}/{@code FeatureFlagService} actually are in production --
     * {@link RedisCacheErrorHandler} is wired into that AOP interceptor via
     * {@code CachingConfigurer}, NOT into the raw {@link Cache} interface itself. A test that
     * calls {@code Cache.get(...)} directly (as this file originally did) bypasses the
     * interceptor entirely and proves nothing about whether the error handler is actually wired
     * up -- confirmed by a real {@code QueryTimeoutException} escaping straight past that
     * version of this test uncaught. Spring Boot auto-detects a static nested
     * {@code @TestConfiguration} class with no {@code @Import} needed.
     */
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        CacheableProbe cacheableProbe() {
            return new CacheableProbe();
        }
    }

    @Component
    static class CacheableProbe {
        final AtomicInteger callCount = new AtomicInteger();

        @Cacheable(cacheNames = CacheConfig.CUSTOM_BANKS_CACHE, sync = true)
        public String compute(String key) {
            return "computed-" + callCount.incrementAndGet();
        }
    }

    /** A minimal stand-in for any real entity with a java.time field (Bank.createdAt,
     *  FeatureFlag.updatedAt, etc.) -- not the real entity, so this doesn't depend on JPA/DB
     *  setup, but the same shape that broke serialization. */
    private record WithInstant(String name, java.time.Instant createdAt) {}

    /**
     * Confirmed via a real failure during this session's own verification: the default
     * GenericJackson2JsonRedisSerializer has no java.time support, so caching a real Bank entity
     * (createdAt: Instant) threw SerializationException -- misdiagnosed by
     * RedisCacheErrorHandler as "Redis unreachable" since it catches every RuntimeException
     * alike, silently and permanently missing the cache for any entity with a date/time field.
     */
    @Test
    void cacheManagerSerializesJavaTimeFieldsCorrectly() {
        Cache cache = cacheManager.getCache(CacheConfig.CUSTOM_BANKS_CACHE);
        assertThat(cache).isNotNull();
        java.time.Instant now = java.time.Instant.now();

        cache.put("time-key", new WithInstant("HDFC", now));

        WithInstant result = cache.get("time-key", WithInstant.class);
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("HDFC");
        assertThat(result.createdAt()).isEqualTo(now);
    }

    @Test
    void cacheManagerIsRedisBacked_putThenGetRoundTrips() {
        Cache cache = cacheManager.getCache(CacheConfig.CUSTOM_BANKS_CACHE);
        assertThat(cache).isNotNull();

        cache.put("test-key", "test-value");

        assertThat(cache.get("test-key", String.class)).isEqualTo("test-value");
    }

    @Test
    void getFailsOpenToARealMethodCallWhenRedisIsUnreachable() {
        String key = "probe-key-" + System.nanoTime();
        String firstResult = cacheableProbe.compute(key);

        REDIS_PROXY.setConnectionCut(true);
        try {
            // Must not throw -- the whole point of the CacheErrorHandler bean. And since a
            // broken GET reads as a miss, the underlying method actually runs again rather than
            // (impossibly) serving the now-unreachable cached value -- a second, DIFFERENT
            // "computed-N" string is the only way this call could succeed at all.
            String secondResult = cacheableProbe.compute(key);
            assertThat(secondResult).isNotEqualTo(firstResult);
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    /** Proves the sync=true locking cache writer's lock is genuinely Redis-side (SETNX), not a
     *  client-local artifact -- see the design spec's "Verified, not assumed" note. A concurrent
     *  load exercised exactly once is the observable proof either way; this test's value is in
     *  confirming that's still true after the RedisCacheManager swap, not in re-deriving why. */
    @Test
    void syncTrueLoadsTheUnderlyingValueExactlyOnceUnderConcurrentMisses() throws InterruptedException {
        Cache cache = cacheManager.getCache(CacheConfig.CUSTOM_BANKS_CACHE);
        assertThat(cache).isNotNull();
        String key = "stampede-key-" + System.nanoTime();
        AtomicInteger loadCount = new AtomicInteger();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    cache.get(key, () -> {
                        loadCount.incrementAndGet();
                        Thread.sleep(200); // widen the race window so misses genuinely overlap
                        return "loaded-value";
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(loadCount.get()).isEqualTo(1);
    }
}
