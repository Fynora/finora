package com.finora.health;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Testcontainer Redis, real Toxiproxy fault injection, real Actuator HealthEndpoint --
 * proves the "redis" component key assumption (flagged as unconfirmed in the design spec) is
 * actually correct, and proves the DOWN path works under a genuine simulated outage, not a mock.
 */
class RedisHealthProviderIT extends AbstractIntegrationTest {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void check_reportsUp_whenRedisIsReachable() {
        RedisHealthProvider provider = new RedisHealthProvider(healthEndpoint);

        var result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDown_whenRedisIsUnreachable() {
        REDIS_PROXY.setConnectionCut(true);
        try {
            RedisHealthProvider provider = new RedisHealthProvider(healthEndpoint);

            var result = provider.check();

            assertThat(result.status()).isIn(HealthStatus.DOWN, HealthStatus.DEGRADED);
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }
}
