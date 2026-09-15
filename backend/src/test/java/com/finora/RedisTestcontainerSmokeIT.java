package com.finora;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the shared Redis Testcontainer + Toxiproxy wiring in AbstractIntegrationTest actually
 * works before any real component depends on it -- same role RedisTestcontainerSmokeIT's name
 * suggests: a fast, obvious failure here means "the infrastructure is broken," not "my feature
 * has a bug," which matters once six other IT classes are built on top of this.
 */
class RedisTestcontainerSmokeIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void theSharedRedisContainerIsReachableThroughTheToxiproxyRoute() {
        redisTemplate.opsForValue().set("smoke-test-key", "smoke-test-value");

        assertThat(redisTemplate.opsForValue().get("smoke-test-key")).isEqualTo("smoke-test-value");
    }
}
