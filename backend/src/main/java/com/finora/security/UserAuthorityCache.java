package com.finora.security;

import com.finora.config.CacheConfig;
import com.finora.util.AfterCommit;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Invalidation for {@link CacheConfig#USER_AUTHORITIES_CACHE}, the one thing the cache's
 * correctness rests on.
 *
 * <p>Both operations run inside {@link AfterCommit#run} rather than as {@code @CacheEvict}: an
 * eviction that fires before the grant commits lets a concurrent request repopulate the entry
 * from the pre-commit row, and the stale set then lives out the whole TTL. Same reasoning as
 * {@code FeatureFlagService.setEnabled} and {@code BankManagementService}. Outside a
 * transaction, {@code AfterCommit.run} executes immediately.
 *
 * <p>Redis being unreachable makes either call a logged no-op ({@code RedisCacheErrorHandler});
 * the entry then expires on its own within the TTL. That window is the documented staleness
 * budget, and it is why the TTL is seconds rather than minutes.
 */
@Component
public class UserAuthorityCache {

    private final CacheManager cacheManager;

    public UserAuthorityCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** One user's authorities changed: a role granted to or revoked from that user. */
    public void evict(UUID userId) {
        AfterCommit.run("evict cached authorities for user " + userId, () -> {
            Cache cache = cacheManager.getCache(CacheConfig.USER_AUTHORITIES_CACHE);
            if (cache != null) {
                cache.evict(userId);
            }
        });
    }

    /** A role's permission set changed, which changes every holder of that role at once. Rather
     *  than enumerate holders (the legacy {@code users.role} column and {@code user_roles} both
     *  count, and the set is small), drop everything. */
    public void clear() {
        AfterCommit.run("clear cached authorities for every user", () -> {
            Cache cache = cacheManager.getCache(CacheConfig.USER_AUTHORITIES_CACHE);
            if (cache != null) {
                cache.clear();
            }
        });
    }
}
