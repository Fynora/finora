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

    /** Audit fix (2026-09-24): an outage used to switch the limiter off entirely. It now counts
     *  in process with the same window and limit, so a request that would have been the
     *  (max+1)th is still refused, and Redis coming back resumes shared counting. */
    @Test
    void allow_countsInProcessWhileRedisIsUnreachable_ratherThanFailingOpen() {
        RateLimiter limiter = new RateLimiter(2, 60, "test-fallback-" + System.nanoTime(), redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThat(limiter.allow("client-a")).isTrue();
            assertThat(limiter.allow("client-a")).isTrue();
            assertThat(limiter.allow("client-a"))
                    .as("third request in the window must be refused even with Redis down")
                    .isFalse();
            assertThat(limiter.allow("client-b"))
                    .as("the fallback is keyed like the real thing: another client has its own window")
                    .isTrue();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
        assertThat(limiter.allow("client-c"))
                .as("shared counting resumes the moment Redis is back")
                .isTrue();
    }

    @Test
    void allow_withAZeroLimit_refusesEvenWhileRedisIsUnreachable() {
        // maxRequests=0 rejects every real request; the previous version of this test asserted
        // the opposite (true during an outage), which was the fail-open behaviour being replaced.
        RateLimiter limiter = new RateLimiter(0, 60, "test-fallback-zero-" + System.nanoTime(), redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThat(limiter.allow("client-a")).isFalse();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    @Test
    void allowLocally_opensAFreshWindowOnceTheOldOneHasElapsed() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1, 1, "test-fallback-window-" + System.nanoTime(), redisTemplate);
        assertThat(limiter.allowLocally("client-a")).isTrue();
        assertThat(limiter.allowLocally("client-a")).isFalse();
        Thread.sleep(1_100);
        assertThat(limiter.allowLocally("client-a")).isTrue();
    }
}
