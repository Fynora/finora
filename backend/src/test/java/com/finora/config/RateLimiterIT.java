package com.finora.config;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void allow_permitsRequestsUpToTheLimitThenRejects() {
        RateLimiter limiter = new RateLimiter(3, 60, "test-basic-" + System.nanoTime(), redisTemplate);

        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
    }

    @Test
    void allow_tracksDistinctKeysIndependently() {
        RateLimiter limiter = new RateLimiter(1, 60, "test-distinct-" + System.nanoTime(), redisTemplate);

        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-b")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
    }

    @Test
    void allow_resetsAfterTheWindowExpires() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1, 1, "test-window-" + System.nanoTime(), redisTemplate);

        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
        Thread.sleep(1_100);
        assertThat(limiter.allow("client-a")).isTrue();
    }

    @Test
    void allow_underConcurrentLoad_permitsExactlyMaxRequests() throws InterruptedException {
        int maxRequests = 10;
        RateLimiter limiter = new RateLimiter(maxRequests, 60, "test-concurrent-" + System.nanoTime(), redisTemplate);
        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (limiter.allow("shared-client")) allowedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(allowedCount.get()).isEqualTo(maxRequests);
    }

    @Test
    void allow_twoLimitersWithDifferentNamesDoNotShareBuckets() {
        String sharedSuffix = "-" + System.nanoTime();
        RateLimiter login = new RateLimiter(1, 60, "login" + sharedSuffix, redisTemplate);
        RateLimiter register = new RateLimiter(1, 60, "register" + sharedSuffix, redisTemplate);

        assertThat(login.allow("same-ip")).isTrue();
        assertThat(register.allow("same-ip")).isTrue();
    }

    @Test
    void allow_failsOpenWhenRedisIsUnreachable() {
        // maxRequests=0 would reject every real request -- if this returns true anyway, that's
        // only explainable by the fail-open path, not by the limiter having room to spare.
        RateLimiter limiter = new RateLimiter(0, 60, "test-failopen-" + System.nanoTime(), redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThat(limiter.allow("client-a")).isTrue();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    @Test
    void allow_failsClosedWhenRedisIsUnreachable_forALimiterBuiltThatWay() {
        RateLimiter limiter = new RateLimiter(100, 60, "test-failclosed-" + System.nanoTime(), redisTemplate, false);
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThat(limiter.decide("client-a")).isEqualTo(RateLimiter.Decision.UNAVAILABLE);
            assertThat(limiter.allow("client-a"))
                    .as("a fail-closed limiter must refuse while its store is unreachable")
                    .isFalse();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
        assertThat(limiter.allow("client-a"))
                .as("and must resume normal service the moment Redis is back")
                .isTrue();
    }

    @Test
    void decide_distinguishesOverTheLimitFromUnavailable() {
        RateLimiter limiter = new RateLimiter(1, 60, "test-decide-" + System.nanoTime(), redisTemplate);
        assertThat(limiter.decide("client-a")).isEqualTo(RateLimiter.Decision.ALLOWED);
        assertThat(limiter.decide("client-a")).isEqualTo(RateLimiter.Decision.LIMITED);
    }
}
