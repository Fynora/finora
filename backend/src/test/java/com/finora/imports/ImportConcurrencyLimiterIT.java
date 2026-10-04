package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportConcurrencyLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String LEASE_SET_KEY = "import:concurrency:active";

    // Production deliberately hardcodes ONE global lease-set key (there is exactly one app-wide
    // import concurrency limit, unlike RateLimiter's per-endpoint keyspace) -- so every test
    // method in this class shares that same Redis key with no isolation of its own. Several tests
    // here (deliberately) leave a lease unreleased to prove a rejection or a prune; without this
    // reset, that leftover state bleeds into whichever test runs next, most visibly wherever
    // maxConcurrent=1 -- the same "shared state, reset before every test" discipline
    // AbstractIntegrationTest's own emptyTheSharedWorkQueues()/resetRedisProxy() already apply.
    @BeforeEach
    void resetLeaseSet() {
        redisTemplate.delete(LEASE_SET_KEY);
    }

    private ImportConcurrencyLimiter newLimiter(int maxConcurrent) {
        return new ImportConcurrencyLimiter(maxConcurrent, 300, redisTemplate);
    }

    @Test
    void acquireRedisLease_grantsUpToMaxConcurrentThenRejects() {
        ImportConcurrencyLimiter limiter = newLimiter(2);

        String lease1 = limiter.acquireRedisLease();
        String lease2 = limiter.acquireRedisLease();
        String lease3 = limiter.acquireRedisLease();

        assertThat(lease1).isNotNull();
        assertThat(lease2).isNotNull();
        assertThat(lease3).isNull();
    }

    @Test
    void releaseRedisLease_freesASlotForANewAcquire() {
        ImportConcurrencyLimiter limiter = newLimiter(1);
        String lease1 = limiter.acquireRedisLease();
        assertThat(lease1).isNotNull();
        assertThat(limiter.acquireRedisLease()).isNull();

        limiter.releaseRedisLease(lease1);

        assertThat(limiter.acquireRedisLease()).isNotNull();
    }

    /** The Redis clock (TIME, whole seconds) the acquire script itself scores and prunes against. */
    private long redisNowSeconds() {
        return redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.serverCommands().time(TimeUnit.SECONDS));
    }

    /** Backdates an existing lease so it reads as acquired `ageSeconds` ago on the Redis clock --
     *  the deterministic stand-in for sleeping past (or short of) the safety TTL.
     *
     *  Why not sleep: the acquire script scores leases with Redis TIME truncated to whole seconds
     *  and prunes every score <= now - safetyTtlSeconds, so the earliest a later acquire can prune a
     *  lease is anywhere from just over ttl - 1 to exactly ttl seconds after it was granted. With the 1-second TTL these tests used to run on, a lease acquired at
     *  x.999 was pruned by an acquire at (x+1).000 -- measured: about 1 in 1,000 back-to-back
     *  acquire pairs, with under a millisecond between them, every one straddling a second
     *  boundary. That is how a "slot genuinely full right now" assertion failed under full-suite
     *  load. A 300-second TTL with backdated scores keeps every lease the test means to be live
     *  hundreds of seconds clear of that edge. */
    private void ageLease(String leaseId, long ageSeconds) {
        assertThat(redisTemplate.opsForZSet().score(LEASE_SET_KEY, leaseId))
                .as("lease %s must exist before it is aged", leaseId).isNotNull();
        redisTemplate.opsForZSet().add(LEASE_SET_KEY, leaseId, redisNowSeconds() - ageSeconds);
    }

    /** The precise failure this design closes, found tracing a shared-counter design during
     *  review: a lease whose holder never releases (a simulated crash) must be pruned by a LATER
     *  acquire's own housekeeping, without disturbing a different, still-live lease. */
    @Test
    void aLeakedLeaseIsPrunedByALaterAcquireWithoutDisturbingOtherLiveLeases() {
        // maxConcurrent=2, both slots taken: the only way a third acquire can succeed at all is if
        // one of them was genuinely pruned, not just tolerated by having room to spare -- and the
        // assertions below pin down that it was the leaked one.
        ImportConcurrencyLimiter limiter = newLimiter(2); // 300s safety TTL
        String live = limiter.acquireRedisLease();
        String leaked = limiter.acquireRedisLease();
        assertThat(live).isNotNull();
        assertThat(leaked).isNotNull();
        // "leaked" is never released -- simulating a crashed holder.
        assertThat(limiter.acquireRedisLease()).isNull(); // both slots genuinely full right now

        // Bracket the 300s TTL: "leaked" is just past it, "live" is still well inside it, so a prune
        // that ignored the TTL (removing everything older than "now") would wrongly take "live" too.
        ageLease(leaked, 301);
        ageLease(live, 270);

        // The prune step inside THIS acquire is what removes "leaked" -- if it didn't, this would
        // still be null (maxConcurrent=2, both slots occupied).
        String third = limiter.acquireRedisLease();
        assertThat(third).isNotNull();
        assertThat(redisTemplate.opsForZSet().score(LEASE_SET_KEY, leaked)).isNull();
        assertThat(redisTemplate.opsForZSet().score(LEASE_SET_KEY, live)).isNotNull();
        assertThat(limiter.acquireRedisLease()).isNull(); // "live" and "third" still hold both slots

        limiter.releaseRedisLease(live);
        limiter.releaseRedisLease(third);
    }

    @Test
    void releasingALeaseThatWasNeverAcquiredIsANoOp() {
        ImportConcurrencyLimiter limiter = newLimiter(1);

        limiter.releaseRedisLease("never-acquired-" + java.util.UUID.randomUUID());

        assertThat(limiter.acquireRedisLease()).isNotNull();
    }

    /** Simulates Redis having genuinely restarted and lost its data (not just a network blip) --
     *  by deleting the lease-set key directly rather than killing the shared Testcontainer, which
     *  AbstractIntegrationTest's own doc comment explicitly forbids (it would break every other
     *  *IT class sharing this JVM's cached Spring context). A real restart and a manual delete
     *  produce the identical observable state (the key is simply gone), so this is a faithful
     *  simulation of the design spec's "Redis restart self-recovers" claim, not a weaker stand-in
     *  for it. */
    @Test
    void releasingALeaseAfterRedisDataWasLostIsANoOpAndNewAcquiresStartFresh() {
        ImportConcurrencyLimiter limiter = newLimiter(1);
        String lease = limiter.acquireRedisLease();
        assertThat(lease).isNotNull();

        redisTemplate.delete("import:concurrency:active"); // simulates a Redis restart with no persistence

        // The "crashed" import's own release, arriving after the simulated restart -- must not
        // throw, and must not do anything harmful to whatever a fresh acquire does next.
        limiter.releaseRedisLease(lease);

        assertThat(limiter.acquireRedisLease()).isNotNull();
    }

    @Test
    void releasingALeaseAfterItWasAlreadyPrunedDoesNotAffectLaterLeases() {
        ImportConcurrencyLimiter limiter = newLimiter(1); // 300s safety TTL; see ageLease() for why
        String pruned = limiter.acquireRedisLease();
        assertThat(pruned).isNotNull();
        ageLease(pruned, 301);
        String replacement = limiter.acquireRedisLease(); // this acquire's own prune step removes "pruned"
        assertThat(replacement).isNotNull();
        assertThat(redisTemplate.opsForZSet().score(LEASE_SET_KEY, pruned)).isNull();

        limiter.releaseRedisLease(pruned); // late release of the already-pruned lease

        assertThat(limiter.acquireRedisLease()).isNull(); // "replacement" must still hold its slot
        limiter.releaseRedisLease(replacement);
    }

    @Test
    void concurrentAcquiresNeverExceedMaxConcurrent() throws InterruptedException {
        int maxConcurrent = 5;
        ImportConcurrencyLimiter limiter = newLimiter(maxConcurrent);
        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        Set<String> granted = ConcurrentHashMap.newKeySet();

        pool.invokeAll(IntStream.range(0, attempts)
                .<Callable<Void>>mapToObj(i -> () -> {
                    String lease = limiter.acquireRedisLease();
                    if (lease != null) granted.add(lease);
                    return null;
                }).toList());
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(granted).hasSizeLessThanOrEqualTo(maxConcurrent);
        granted.forEach(limiter::releaseRedisLease);
    }

    @Test
    void runGated_usesTheRedisLeaseWhenRedisIsReachable() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(2, 300, redisTemplate);

        // Asserts against the Redis-side ZSET directly, not just the return value: a
        // pure-local-Semaphore runGated() (the pre-Task-7 shape) satisfies "returns ok" identically
        // whether or not Redis is ever touched, which would make this test pass for the wrong
        // reason. Checking mid-flight state from inside `work` is what actually proves the Redis
        // lease was acquired, not merely tolerated by an unrelated fallback path.
        String result = limiter.runGated(() -> {
            Long activeLeases = redisTemplate.opsForZSet().size("import:concurrency:active");
            assertThat(activeLeases).isEqualTo(1L);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(redisTemplate.opsForZSet().size("import:concurrency:active")).isEqualTo(0L);
    }

    @Test
    void runGated_rejectsBeyondMaxConcurrentViaRedis() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redisTemplate);
        java.util.concurrent.CountDownLatch holdFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                limiter.runGated(() -> {
                    holdFirst.countDown();
                    releaseFirst.await();
                    return null;
                });
            } catch (Exception ignored) { }
        });
        first.start();
        holdFirst.await();

        assertThatThrownBy(() -> limiter.runGated(() -> "should not run"))
                .isInstanceOf(com.finora.exception.ApiException.class);

        releaseFirst.countDown();
        first.join();
    }

    @Test
    void runGated_fallsBackToTheLocalSemaphoreWhenRedisIsUnreachable() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(2, 300, redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            String result = limiter.runGated(() -> "ok via local fallback");

            assertThat(result).isEqualTo("ok via local fallback");
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    @Test
    void runGated_localFallbackStillEnforcesMaxConcurrent() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            java.util.concurrent.CountDownLatch holdFirst = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
            Thread first = new Thread(() -> {
                try {
                    limiter.runGated(() -> {
                        holdFirst.countDown();
                        releaseFirst.await();
                        return null;
                    });
                } catch (Exception ignored) { }
            });
            first.start();
            holdFirst.await();

            assertThatThrownBy(() -> limiter.runGated(() -> "should not run"))
                    .isInstanceOf(com.finora.exception.ApiException.class);

            releaseFirst.countDown();
            first.join();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    /** The specific detail flagged during design review: a permit acquired via one mechanism must
     *  release via that SAME mechanism, even if Redis's reachability changes between acquire and
     *  release. Cutting the connection AFTER acquire (which succeeds via Redis) proves release
     *  still correctly targets Redis rather than silently no-op'ing into the local semaphore. */
    @Test
    void aPermitAcquiredViaRedisReleasesViaRedisEvenIfRedisReachabilityChangesMidRequest() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redisTemplate);

        limiter.runGated(() -> {
            // Acquired via Redis (connection was live). Cut it now, mid-request.
            REDIS_PROXY.setConnectionCut(true);
            return null;
        });
        REDIS_PROXY.setConnectionCut(false);

        // If release had gone to the wrong place, the Redis lease from above would still be held,
        // and this acquire (now that Redis is reachable again) would be rejected.
        String result = limiter.runGated(() -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    /**
     * The exact bug found during a post-implementation review: an earlier version of
     * acquirePermit() tried the Redis lease FIRST and only touched the local Semaphore on a Redis
     * exception, leaving the two mechanisms as fully independent pools. Two imports already
     * running via genuine Redis leases (maxConcurrent slots fully used on the Redis side) never
     * touched the local semaphore at all -- so a THIRD request arriving during a transient,
     * single-call Redis blip (not a clean whole-instance outage) fell back to a completely
     * UNTOUCHED local semaphore with maxConcurrent slots still free, and was wrongly admitted,
     * letting this one instance run up to 2x maxConcurrent concurrently. The fix makes the local
     * semaphore the unconditional first gate on every acquire, so it always reflects true current
     * local usage regardless of which mechanism actually backs each held permit.
     */
    @Test
    void aTransientRedisBlipOnOneRequestNeverLetsThisInstanceExceedMaxConcurrent() throws Exception {
        int maxConcurrent = 2;
        ImportConcurrencyLimiter limiter = newLimiter(maxConcurrent);
        CountDownLatch bothHeld = new CountDownLatch(2);
        CountDownLatch releaseBoth = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    limiter.runGated(() -> {
                        bothHeld.countDown();
                        releaseBoth.await();
                        return null;
                    });
                } catch (Exception ignored) { }
            });
        }
        bothHeld.await();

        // Both slots are now held via real Redis leases (ZCARD == maxConcurrent, confirmed by
        // acquireRedisLease_grantsUpToMaxConcurrentThenRejects's own coverage of that mechanism).
        // Cutting Redis here simulates a blip affecting only this THIRD, separate request -- not
        // a clean outage the two already-running imports are also experiencing.
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThatThrownBy(() -> limiter.runGated(() -> "should never run"))
                    .isInstanceOf(com.finora.exception.ApiException.class);
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }

        releaseBoth.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }
}
