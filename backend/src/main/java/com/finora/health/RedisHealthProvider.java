package com.finora.health;

import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Wraps the same Actuator HealthEndpoint bean DatabaseHealthProvider already uses, reading the
 * "redis" component Spring Boot Actuator auto-configures once spring-boot-starter-data-redis and
 * a reachable connection are both present -- see docs/superpowers/specs/
 * 2026-09-15-redis-integration-design.md's "Ongoing health visibility" section for why this
 * exists: the design deliberately never hard-fails boot on a missing/unreachable Redis, so a
 * wrong REDISHOST/REDISPORT would otherwise be silently invisible past one boot-time log line.
 * Redis was explicitly named in HealthProvider's own class doc as infrastructure this codebase
 * didn't have a provider for yet -- this is that provider, now that it exists.
 */
@Component
public class RedisHealthProvider implements HealthProvider {

    private final HealthEndpoint healthEndpoint;

    public RedisHealthProvider(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
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
        HealthComponent root = healthEndpoint.health();
        if (!(root instanceof CompositeHealth composite)) {
            return HealthCheckResult.degraded("Actuator health tree has no components to inspect");
        }
        HealthComponent redisComponent = composite.getComponents().get("redis");
        if (redisComponent == null) {
            return HealthCheckResult.degraded(
                    "No \"redis\" component in Actuator's health tree -- Redis may not be configured");
        }
        Status status = redisComponent.getStatus();
        String detail = "Actuator redis indicator: " + status;
        if (status == Status.UP) return HealthCheckResult.up(detail);
        if (status == Status.DOWN) return HealthCheckResult.down(detail);
        return HealthCheckResult.degraded(detail);
    }
}
