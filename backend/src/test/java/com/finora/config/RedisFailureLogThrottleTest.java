package com.finora.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RedisFailureLogThrottleTest {

    @Test
    void warn_logsOnTheFirstCall() {
        Logger logger = mock(Logger.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
        RedisFailureLogThrottle throttle = new RedisFailureLogThrottle(logger, 60_000, clock);

        throttle.warn("Redis unreachable: {}", "boom");

        // SLF4J's Logger declares warn(String,Object), warn(String,Object,Object) AND
        // warn(String,Object...) as distinct overloads -- RedisFailureLogThrottle.warn's own
        // Object... args parameter, once inside the method body, has static type Object[], so
        // Java's most-specific-method rule always resolves log.warn(message, args) to the
        // varargs overload. A verify() call passing bare String/Object arguments resolves to a
        // DIFFERENT (fixed-arity) overload and never matches -- eq(Object[]) forces the same
        // overload actual production code goes through.
        verify(logger).warn(eq("Redis unreachable: {}"), eq(new Object[] {"boom"}));
    }

    @Test
    void warn_suppressesASecondCallWithinTheThrottleWindow() {
        Logger logger = mock(Logger.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
        RedisFailureLogThrottle throttle = new RedisFailureLogThrottle(logger, 60_000, clock);

        throttle.warn("first");
        throttle.warn("second");

        verify(logger, times(1)).warn(anyString(), any(Object[].class));
    }

    @Test
    void warn_logsAgainOnceTheThrottleWindowHasPassed() {
        Logger logger = mock(Logger.class);
        Instant start = Instant.parse("2026-09-15T00:00:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(start);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RedisFailureLogThrottle throttle = new RedisFailureLogThrottle(logger, 60_000, clock);

        throttle.warn("first");
        now.set(start.plusMillis(60_001));
        throttle.warn("second");

        verify(logger, times(2)).warn(anyString(), any(Object[].class));
    }
}
