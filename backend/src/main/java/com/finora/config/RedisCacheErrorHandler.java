package com.finora.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Treats every Redis-backed cache failure as a miss/no-op rather than letting it propagate --
 * see docs/superpowers/specs/2026-09-15-redis-integration-design.md, Component 3: a broken
 * CacheManager otherwise turns every @Cacheable call into a thrown exception, strictly worse
 * than having no cache at all. Logging goes through the same shared, purely-local
 * RedisFailureLogThrottle every other Redis-backed component uses -- never anything Redis-backed
 * itself, which would be a circular dependency on the one message that most needs to still work
 * when Redis is down.
 */
public class RedisCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheErrorHandler.class);
    private final RedisFailureLogThrottle failureLog = new RedisFailureLogThrottle(log, 60_000);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        failureLog.warn("Redis unreachable on cache get ({}) -- treating as a miss: {}",
                cache.getName(), exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        failureLog.warn("Redis unreachable on cache put ({}) -- write dropped: {}",
                cache.getName(), exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        failureLog.warn("Redis unreachable on cache evict ({}) -- stale entry may persist until "
                + "its own TTL: {}", cache.getName(), exception.toString());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        failureLog.warn("Redis unreachable on cache clear ({}): {}", cache.getName(), exception.toString());
    }
}
