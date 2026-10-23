package com.finora.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gives an expiry to any cache lock left in Redis without one.
 *
 * <p>Builds before the fix in {@link CacheConfig} wrote each {@code sync = true} load's lock with no
 * expiry. When a load's unlock never reached Redis -- a timed-out command, or the process stopping
 * mid-load -- the lock stayed, and Spring Data Redis waits for a lock with no time limit. The lock
 * is per cache, not per key, and every read of that cache waits on it, cached values included. So
 * every later read hung while holding its request's database connection; that is what took
 * production down on 2026-10-23. A restart does not help: the lock lives in Redis.
 *
 * <p>Locks written now always carry {@link CacheConfig#CACHE_LOCK_TTL}, so a lock with no expiry
 * can only be one an older build wrote. Giving it the same expiry, rather than deleting it, means a
 * load still running on an older instance during a deploy keeps its lock for a few seconds.
 *
 * <p>Runs at startup and then every minute: during a deploy the old instance keeps serving, and
 * can leave such a lock after this instance's startup check, and Redis may be down at startup.
 */
@Component
public class CacheLockExpiry {

    private static final Logger log = LoggerFactory.getLogger(CacheLockExpiry.class);

    private final CacheManager cacheManager;
    private final StringRedisTemplate redis;
    private final RedisFailureLogThrottle failureLog = new RedisFailureLogThrottle(log, 600_000);

    @Value("${app.cache.lock-repair.enabled:true}")
    private boolean scheduledRepairEnabled = true;

    public CacheLockExpiry(CacheManager cacheManager, StringRedisTemplate redis) {
        this.cacheManager = cacheManager;
        this.redis = redis;
    }

    /** The key Spring Data Redis's locking cache writer uses for a cache's lock. */
    static String lockKey(String cacheName) {
        return cacheName + "~lock";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        expireLocksLeftWithoutOne();
    }

    @Scheduled(fixedDelayString = "${app.cache.lock-repair.interval-ms:60000}",
            initialDelayString = "${app.cache.lock-repair.interval-ms:60000}")
    public void scheduledRepair() {
        if (scheduledRepairEnabled) expireLocksLeftWithoutOne();
    }

    /** @return how many locks were given an expiry. Never throws: Redis being down must not stop
     *  the app or the scheduler, and the cache already treats it as a miss. */
    public int expireLocksLeftWithoutOne() {
        int fixed = 0;
        for (String name : cacheManager.getCacheNames()) {
            String key = lockKey(name);
            try {
                // -1: the key exists with no expiry. -2: no such key.
                Long ttl = redis.getExpire(key);
                if (ttl != null && ttl == -1L && Boolean.TRUE.equals(redis.expire(key, CacheConfig.CACHE_LOCK_TTL))) {
                    fixed++;
                    log.warn("Cache lock {} had no expiry (an earlier load never released it); it now expires in {}s",
                            key, CacheConfig.CACHE_LOCK_TTL.toSeconds());
                }
            } catch (RuntimeException e) {
                failureLog.warn("Could not check cache lock {}: {}", key, e.toString());
                return fixed;
            }
        }
        return fixed;
    }
}
