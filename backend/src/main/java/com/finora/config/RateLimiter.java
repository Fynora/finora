package com.finora.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;

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
 * <p>Fails OPEN on any {@link DataAccessException} -- covers both a refused connection
 * ({@code RedisConnectionFailureException}) and a timed-out command ({@code
 * QueryTimeoutException}); catching only the narrower connection-failure type would leave this
 * broken under a network partition specifically, the one case the 200ms command/connect timeout
 * (application.yml) exists to catch fast. Rate limiting is a protective control, not a
 * correctness one -- a wrongly-allowed request during a rare outage costs nothing structural.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

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

    public RateLimiter(int maxRequests, long windowSeconds, String limiterName, StringRedisTemplate redisTemplate) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.limiterName = limiterName;
        this.redisTemplate = redisTemplate;
        this.failureLog = new RedisFailureLogThrottle(log, 60_000);
    }

    /** Returns true if the request is allowed, false if the caller has exceeded the limit, and
     *  true (fail open) if Redis could not be reached within the configured timeout. */
    public boolean allow(String key) {
        String redisKey = "ratelimit:" + limiterName + ":" + key;
        try {
            Long result = redisTemplate.execute(ALLOW_SCRIPT, List.of(redisKey),
                    String.valueOf(windowSeconds), String.valueOf(maxRequests), UUID.randomUUID().toString());
            return result != null && result == 1L;
        } catch (DataAccessException e) {
            failureLog.warn("Redis unreachable for rate limiter '{}' -- failing open: {}", limiterName, e.toString());
            return true;
        }
    }
}
