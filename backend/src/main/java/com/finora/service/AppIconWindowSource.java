package com.finora.service;

import com.finora.config.CacheConfig;
import com.finora.notification.campaign.IstClock;
import com.finora.repository.AppIconScheduleRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The cached read behind GET /api/v1/app/icon: every schedule window that had not ended when it was
 * loaded. Its own bean so {@link AppIconService} calls it through Spring's proxy (a
 * {@code @Cacheable} method called from inside its own class never reaches the cache).
 *
 * <p>What is cached is the list of open windows, not the answer "which icon now", so a window
 * still starts and ends at its exact instant while the list is cached: {@link AppIconService}
 * picks from the list against the clock on every call. The list only goes stale when an admin
 * changes the schedule, and every change evicts it after commit; the TTL in {@link CacheConfig}
 * is the safety net if an eviction is ever missed.
 */
@Service
public class AppIconWindowSource {

    /** One cache entry holds the whole list; this is its key. */
    static final String KEY = "open-windows";

    /** One window as cached. The icon stays a key string: see {@code AppIconSchedule}'s class comment. */
    public record IconWindow(String iconKey, Instant startsAt, Instant endsAt) {
    }

    /**
     * The cached value. The list is always an {@link ArrayList}: the Redis serializer writes each
     * value's concrete class into the JSON, and the JDK's immutable list classes cannot be read back.
     */
    public record IconWindows(ArrayList<IconWindow> windows) {
    }

    private final AppIconScheduleRepository repository;
    private final IstClock clock;
    private final CacheManager cacheManager;

    public AppIconWindowSource(AppIconScheduleRepository repository, IstClock clock, CacheManager cacheManager) {
        this.repository = repository;
        this.clock = clock;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = CacheConfig.APP_ICON_SCHEDULE_CACHE, key = "'" + KEY + "'", sync = true)
    public IconWindows openWindows() {
        List<IconWindow> windows = repository.findByEndsAtAfterOrderByStartsAtAsc(clock.now()).stream()
                .map(s -> new IconWindow(s.getIconKey(), s.getStartsAt(), s.getEndsAt()))
                .toList();
        return new IconWindows(new ArrayList<>(windows));
    }

    /** Drops the cached list, so the next read loads the schedule as it now is. */
    public void evict() {
        Cache cache = cacheManager.getCache(CacheConfig.APP_ICON_SCHEDULE_CACHE);
        if (cache != null) cache.evict(KEY);
    }
}
