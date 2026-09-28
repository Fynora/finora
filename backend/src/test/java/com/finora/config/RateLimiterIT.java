package com.finora.config;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

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

    /** Main CI, 2026-09-27: the script used to read Redis TIME in whole
     *  seconds, so with a 1s window a request recorded at second S was trimmed by any call that
     *  landed in second S+1 -- even 20ms later -- and that call was wrongly allowed. This pins
     *  both calls either side of a second boundary, well inside the window, and expects the
     *  second one refused. */
    @Test
    void allow_refusesASecondRequestThatStraddlesASecondBoundaryInsideTheWindow() throws InterruptedException {
        RateLimiter limiter = null;
        long firstCallSecond = -1;
        for (int attempt = 0; attempt < 5 && firstCallSecond < 0; attempt++) {
            limiter = new RateLimiter(1, 1, "test-straddle-" + System.nanoTime(), redisTemplate);
            long[] before = waitForRedisMicrosAbove(950_000);
            assertThat(limiter.allow("client-a")).isTrue();
            long[] after = redisTime();
            if (after[0] == before[0]) {
                firstCallSecond = before[0];
            }
        }
        assertThat(firstCallSecond)
                .as("the first call must land in the last 50ms of a Redis second")
                .isPositive();

        while (redisTime()[0] == firstCallSecond) {
            Thread.sleep(5);
        }
        assertThat(limiter.allow("client-a"))
                .as("under 100ms after the first request, the 1s window still holds it")
                .isFalse();
    }

    /** {seconds, microseconds} from Redis's own clock -- the one the limiter script uses. */
    private long[] redisTime() {
        List<Object> time = redisTemplate.execute(
                new DefaultRedisScript<>("return redis.call('TIME')", List.class), List.of());
        return new long[]{Long.parseLong(time.get(0).toString()), Long.parseLong(time.get(1).toString())};
    }

    private long[] waitForRedisMicrosAbove(long micros) throws InterruptedException {
        long[] time = redisTime();
        while (time[1] <= micros) {
            long waitMs = (micros - time[1]) / 1000;
            Thread.sleep(Math.max(1, Math.min(waitMs, 900)));
            time = redisTime();
        }
        return time;
    }

    /** Keys written before the microsecond change hold epoch-second scores. Trimming those as
     *  ancient would reset every limiter on deploy -- a fresh login window for anyone mid-attack. */
    @Test
    void allow_countsARequestRecordedByTheWholeSecondScriptThatIsStillInsideTheWindow() {
        String name = "test-legacy-" + System.nanoTime();
        RateLimiter limiter = new RateLimiter(1, 60, name, redisTemplate);
        redisTemplate.opsForZSet().add("ratelimit:" + name + ":client-a", "legacy-member", redisTime()[0] - 5);

        assertThat(limiter.allow("client-a"))
                .as("a request 5s before the deploy still fills a 60s window of 1")
                .isFalse();
    }

    @Test
    void allow_dropsARequestRecordedByTheWholeSecondScriptOnceItIsOutsideTheWindow() {
        String name = "test-legacy-expired-" + System.nanoTime();
        RateLimiter limiter = new RateLimiter(1, 60, name, redisTemplate);
        redisTemplate.opsForZSet().add("ratelimit:" + name + ":client-a", "legacy-member", redisTime()[0] - 61);

        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
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
