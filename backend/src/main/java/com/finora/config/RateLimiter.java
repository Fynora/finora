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

    /** What {@link #decide} found. Kept as three states so a fail-closed caller can tell "you sent
     *  too many" (429) from "the limiter itself is down" (503) instead of blaming the client. */
    public enum Decision { ALLOWED, LIMITED, UNAVAILABLE }

    private final int maxRequests;
    private final long windowSeconds;
    private final String limiterName;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailureLogThrottle failureLog;
    private final boolean failOpen;

    public RateLimiter(int maxRequests, long windowSeconds, String limiterName, StringRedisTemplate redisTemplate) {
        this(maxRequests, windowSeconds, limiterName, redisTemplate, true);
    }

    /**
     * @param failOpen what {@link #allow} answers when Redis cannot be reached. True is right for
     *        most limiters: a Redis outage must not take the whole API down with it. False is for
     *        the few endpoints whose per-call cost is the thing being protected (bcrypt on login
     *        and registration, the OTP send): there, an unthrottled minute during an outage is
     *        exactly the window a CPU-exhaustion attacker waits for, and refusing with a 503
     *        costs a legitimate user one retry.
     */
    public RateLimiter(int maxRequests, long windowSeconds, String limiterName, StringRedisTemplate redisTemplate,
                       boolean failOpen) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.limiterName = limiterName;
        this.redisTemplate = redisTemplate;
        this.failureLog = new RedisFailureLogThrottle(log, 60_000);
        this.failOpen = failOpen;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    /** {@link #decide}, collapsed to a boolean by this limiter's fail-open policy: an
     *  unreachable Redis counts as allowed for a fail-open limiter and as refused otherwise. */
    public boolean allow(String key) {
        Decision decision = decide(key);
        return decision == Decision.ALLOWED || (decision == Decision.UNAVAILABLE && failOpen);
    }

    /** The raw verdict: allowed, over the limit, or Redis could not be reached within the
     *  configured timeout. */
    public Decision decide(String key) {
        String redisKey = "ratelimit:" + limiterName + ":" + key;
        try {
            Long result = redisTemplate.execute(ALLOW_SCRIPT, List.of(redisKey),
                    String.valueOf(windowSeconds), String.valueOf(maxRequests), UUID.randomUUID().toString());
            return result != null && result == 1L ? Decision.ALLOWED : Decision.LIMITED;
        } catch (DataAccessException e) {
            failureLog.warn("Redis unreachable for rate limiter '{}' -- failing {}: {}", limiterName,
                    failOpen ? "open" : "closed", e.toString());
            return Decision.UNAVAILABLE;
        }
    }
}
