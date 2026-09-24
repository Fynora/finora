package com.finora.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sliding-window (log-based) rate limiter, Redis-backed -- see docs/superpowers/specs/
 * 2026-09-15-redis-integration-design.md, Component 1. One atomic Lua script per allow() call
 * (a single EVAL) is what makes the trim/count/record sequence race-free across replicas and even
 * within one instance -- the exact multi-replica gap the in-process predecessor of this class had.
 *
 * <p>The script asks Redis for its own clock (TIME) rather than trusting each application
 * replica's local clock -- a deliberate improvement over the design spec's own pseudocode,
 * closing a clock-skew-between-replicas class of bug for free, since Redis is the single shared
 * timing authority every replica already agrees on by construction.
 *
 * <h2>When Redis is unreachable</h2>
 * On any {@link DataAccessException} -- a refused connection ({@code
 * RedisConnectionFailureException}) or a timed-out command ({@code QueryTimeoutException}, the
 * case the 200ms command/connect timeout in application.yml exists to catch fast) -- this falls
 * back to counting in process, in a fixed window of the same size and limit, keyed the same way.
 *
 * <p>This used to fail fully open, on the reasoning that a wrongly-allowed request during a rare
 * outage costs nothing structural. That is true of most routes and false of the ones whose per-call
 * cost is the point: login and registration each spend a bcrypt(12) verification (~250ms of CPU),
 * so an unthrottled minute is exactly the window a CPU-exhaustion attacker waits for (audit,
 * 2026-09-24). Failing closed instead (a 503 until Redis is back) was tried and rejected the same
 * day: it takes sign-in away from every real user for the whole outage, and the CI smoke job --
 * which runs the backend with no Redis at all -- could not sign in either. In-process counting
 * keeps the limiter's shape during an outage; what it loses is coordination across replicas,
 * which on a single instance is nothing, and on N instances is an N-times-looser limit for the
 * outage's duration rather than no limit at all.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Distinct keys the in-process fallback will hold before it is cleared wholesale. Bounds the
     *  map against an attacker cycling source addresses during an outage; clearing hands out one
     *  fresh window to everyone, which is the same cost as one more minute of the outage. */
    static final int MAX_FALLBACK_KEYS = 100_000;

    private static final DefaultRedisScript<Long> ALLOW_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local windowSeconds = tonumber(ARGV[1])
            local maxRequests = tonumber(ARGV[2])
            local member = ARGV[3]

            local time = redis.call('TIME')
            local now = tonumber(time[1])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowSeconds)
            local count = redis.call('ZCARD', key)
            if count >= maxRequests then
              return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('EXPIRE', key, windowSeconds)
            return 1
            """, Long.class);

    private final int maxRequests;
    private final long windowSeconds;
    private final String limiterName;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailureLogThrottle failureLog;

    /** key -> {window start (epoch seconds), count in that window}. Only touched while Redis is
     *  unreachable; entries from a previous outage are stale by then and get replaced on first use. */
    private final ConcurrentHashMap<String, long[]> fallbackWindows = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowSeconds, String limiterName, StringRedisTemplate redisTemplate) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.limiterName = limiterName;
        this.redisTemplate = redisTemplate;
        this.failureLog = new RedisFailureLogThrottle(log, 60_000);
    }

    /** True if the request is allowed, false if the caller has exceeded the limit. When Redis
     *  cannot be reached the answer comes from {@link #allowLocally} instead, so an outage
     *  degrades the limiter to per-instance rather than switching it off. */
    public boolean allow(String key) {
        String redisKey = "ratelimit:" + limiterName + ":" + key;
        try {
            Long result = redisTemplate.execute(ALLOW_SCRIPT, List.of(redisKey),
                    String.valueOf(windowSeconds), String.valueOf(maxRequests), UUID.randomUUID().toString());
            return result != null && result == 1L;
        } catch (DataAccessException e) {
            failureLog.warn("Redis unreachable for rate limiter '{}' -- counting in process until it is back: {}",
                    limiterName, e.toString());
            return allowLocally(key);
        }
    }

    /** Fixed window, same size and limit as the Redis script, on this instance's own clock. */
    boolean allowLocally(String key) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        if (fallbackWindows.size() > MAX_FALLBACK_KEYS) {
            fallbackWindows.clear();
        }
        long[] window = fallbackWindows.compute(key, (k, current) -> {
            if (current == null || nowSeconds - current[0] >= windowSeconds) {
                return new long[]{nowSeconds, 1};
            }
            current[1]++;
            return current;
        });
        return window[1] <= maxRequests;
    }
}
