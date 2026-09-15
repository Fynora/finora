package com.finora.config;

import org.slf4j.Logger;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A purely local, in-JVM "log at most once per N milliseconds" guard -- see
 * docs/superpowers/specs/2026-09-15-redis-integration-design.md's log-throttling note: this must
 * never be backed by Redis itself, including this design's own new Redis-backed rate limiter.
 * The one moment this throttle needs to work is exactly the moment the thing it might otherwise
 * depend on is down.
 *
 * <p>Takes an injectable {@code Clock} the same way several other test-seam classes in this
 * package do -- a real timer-based test would either not run or not be a real test.
 */
public class RedisFailureLogThrottle {

    private final Logger log;
    private final long minIntervalMillis;
    private final Clock clock;
    // Long.MIN_VALUE / 2, not Long.MIN_VALUE: "now - last" below would otherwise overflow a
    // signed long on the very first call (now, a real epoch-millis value, minus Long.MIN_VALUE
    // wraps around to a large NEGATIVE number instead of a large positive one), silently making
    // the first warn() call a no-op. Traced via a failing test before this was caught: the mock
    // logger recorded zero interactions even on the first call.
    private final AtomicLong lastLoggedAtMillis = new AtomicLong(Long.MIN_VALUE / 2);

    public RedisFailureLogThrottle(Logger log, long minIntervalMillis) {
        this(log, minIntervalMillis, Clock.systemUTC());
    }

    /** Package-private test seam, same reasoning as {@link RateLimiter}'s equivalent constructor. */
    RedisFailureLogThrottle(Logger log, long minIntervalMillis, Clock clock) {
        this.log = log;
        this.minIntervalMillis = minIntervalMillis;
        this.clock = clock;
    }

    public void warn(String message, Object... args) {
        long now = clock.millis();
        long last = lastLoggedAtMillis.get();
        if (now - last >= minIntervalMillis && lastLoggedAtMillis.compareAndSet(last, now)) {
            log.warn(message, args);
        }
    }
}
