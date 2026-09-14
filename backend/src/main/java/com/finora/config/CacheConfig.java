package com.finora.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * The one place named caches get registered -- see docs/superpowers/specs/
 * 2026-09-15-redis-integration-design.md, Component 3, for why this moved from Caffeine
 * (in-process, correct on one instance only) to Redis (shared, correct across replicas), per
 * ADR-008's own stated trigger condition ("once a second instance exists"). Same two named
 * caches, same TTLs, same {@code sync = true} stampede-protection contract --
 * {@code BankManagementService} and {@code FeatureFlagService}'s existing
 * {@code @Cacheable}/{@code @CacheEvict} annotations are untouched by this change; only this
 * bean's own implementation changed.
 *
 * <h2>Cache-stampede protection is {@code sync = true}, not a separate mechanism</h2>
 *
 * <p>Every {@code @Cacheable} using one of these caches should set {@code sync = true}. Spring
 * resolves that to {@code Cache.get(key, Callable)}, which {@link RedisCacheWriter}'s locking
 * variant below backs with a genuine Redis-side {@code SETNX} lock (verified against Spring Data
 * Redis's own source, not assumed -- see the design spec's "Verified, not assumed" note), so
 * concurrent callers across every replica that miss the same key block behind the first load
 * rather than each independently repeating the expensive work.
 *
 * <h2>Fails open to a cache miss, not an exception</h2>
 *
 * <p>{@link #errorHandler()} below routes every Redis failure through {@link RedisCacheErrorHandler},
 * which treats it as a miss/no-op rather than letting it propagate -- a broken cache should never
 * be worse than no cache.
 *
 * <h2>Adding a new cached resource</h2>
 *
 * <p>Register a name + {@link RedisCacheConfiguration} below, sized for that resource's own
 * staleness tolerance, then use {@code @Cacheable(cacheNames = "...", sync = true)} /
 * {@code @CacheEvict(cacheNames = "...")} on the read/write methods -- see
 * {@code BankManagementService} and {@code FeatureFlagService} for the pattern, including the
 * self-invocation pitfall both work around (a {@code @Cacheable} method is only intercepted
 * through Spring's proxy, so calling it via {@code this.} from inside the same class silently
 * never hits the cache).
 *
 * <h2>Diagnostics</h2>
 *
 * <p>{@code AdminDiagnosticsService} already reports whether a {@link CacheManager} bean exists
 * (it injects one as an {@code ObjectProvider} specifically to answer that) -- this bean is what
 * flips that diagnostic from false to true, not a new field added to make it so.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    /** Admin-managed custom banks ({@code BankManagementService}) -- a small, rarely-changing
     *  dataset mutated only through admin CRUD, currently re-queried from Postgres on every
     *  account read (the accounts-listing N+1 named in {@code project-plan-v1.0.md} §5a). TTL is a
     *  safety net, not the primary invalidation path: {@code BankManagementService} evicts
     *  explicitly on create/update/delete, so an admin's own change is visible immediately rather
     *  than waiting out the window. */
    public static final String CUSTOM_BANKS_CACHE = "customBanks";

    /** Feature flags ({@code FeatureFlagService}) -- admin-toggled booleans, read on every call to
     *  {@code RecurringService.detectForUser} (8 call sites: every import confirm and every
     *  transaction mutation). Same explicit-eviction-plus-TTL-safety-net shape as the bank cache,
     *  with a shorter TTL: a flag gates real behavior, so bounding staleness tighter costs nothing
     *  against a dataset this small and buys a faster self-heal if an eviction path is ever missed. */
    public static final String FEATURE_FLAGS_CACHE = "featureFlags";

    /** {@code CachingConfigurer} is required here, not optional -- verified against Spring's own
     *  caching docs: a plain {@code @Bean CacheErrorHandler} is never auto-wired by
     *  {@code @EnableCaching} on its own; Spring falls back to the default
     *  {@code SimpleCacheErrorHandler} (which rethrows) unless a {@code CachingConfigurer}
     *  explicitly returns the custom handler from {@link #errorHandler()} below. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler();
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        // lockingRedisCacheWriter: the Redis-side SETNX-backed lock sync=true needs -- see the
        // class doc above and the design spec's "Verified, not assumed" note.
        RedisCacheWriter writer = RedisCacheWriter.lockingRedisCacheWriter(connectionFactory);

        return RedisCacheManager.builder(writer)
                .cacheDefaults(defaultConfig.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration(CUSTOM_BANKS_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration(FEATURE_FLAGS_CACHE, defaultConfig.entryTtl(Duration.ofSeconds(60)))
                .build();
    }
}
