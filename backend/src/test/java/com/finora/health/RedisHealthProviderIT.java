package com.finora.health;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Testcontainer Redis, real Toxiproxy fault injection -- proves both the UP path (a real
 * PING succeeds) and the DOWN path (a genuine simulated outage) of the direct probe, not a mock.
 * Does not go through Actuator's HealthEndpoint: management.health.redis.enabled is false (see
 * application.yml's own comment on why), so that component no longer exists in the aggregate
 * tree at all -- this provider is the only remaining Redis health signal.
 */
class RedisHealthProviderIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void check_reportsUp_whenRedisIsReachable() {
        RedisHealthProvider provider = new RedisHealthProvider(redisTemplate);

        var result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDown_whenRedisIsUnreachable() {
        REDIS_PROXY.setConnectionCut(true);
        try {
            RedisHealthProvider provider = new RedisHealthProvider(redisTemplate);

            var result = provider.check();

            assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }
}
