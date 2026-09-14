package com.finora.health;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Probes Redis directly (a PING through the app's own {@link StringRedisTemplate}, subject to
 * the same 200ms {@code spring.data.redis.timeout} every other Redis-backed component here
 * respects) rather than reading Spring Boot's auto-configured "redis" Actuator component --
 * that component is deliberately disabled via {@code management.health.redis.enabled: false}
 * (see application.yml's own comment) after a real CI failure showed it joining the SAME
 * aggregate {@code /actuator/health} uses for its overall status/HTTP code. With it left
 * enabled, Redis being unreachable flipped the WHOLE endpoint to DOWN/503 -- directly
 * contradicting this design's "Redis-optional, fails open" principle, and not just a CI
 * inconvenience: any deploy/readiness check gating on that endpoint (this repo's own CI jobs,
 * plausibly Railway's own health check) would treat a transient Redis blip as "the entire
 * backend is down." Probing directly here keeps admin diagnostics visibility for Redis working
 * (see docs/superpowers/specs/2026-09-15-redis-integration-design.md's "Ongoing health
 * visibility" section) without coupling it back to that aggregate.
 */
@Component
public class RedisHealthProvider implements HealthProvider {

    private final StringRedisTemplate redisTemplate;

    public RedisHealthProvider(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String name() {
        return "Redis";
    }

    @Override
    public String category() {
        return "Platform";
    }

    @Override
    public HealthCheckResult check() {
        try {
            String pong = redisTemplate.execute(
                    (org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                return HealthCheckResult.up("Redis PING succeeded");
            }
            return HealthCheckResult.degraded("Redis PING returned an unexpected reply: " + pong);
        } catch (DataAccessException e) {
            return HealthCheckResult.down("Redis PING failed: " + e.toString());
        }
    }
}
