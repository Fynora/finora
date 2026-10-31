package com.finora.imports.convert;

import com.finora.config.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * How often the free converter may be used: per visitor address per hour and per day, and by
 * everyone together per day (design §4.1, Sid 2026-10-31: 3, 10 and 300). Counted only once a
 * request has passed Turnstile, so a script without tokens cannot use up a real visitor's allowance.
 *
 * <p>The address is the one {@code ClientIpResolver} resolves (correct behind Cloudflare and Railway
 * since #2366). It lives only in the limiter's Redis keys, which expire with the longest window
 * (24 hours) -- the one piece of the visit the converter keeps.
 */
@Component
public class ConverterLimits {

    static final String GLOBAL_KEY = "all";

    private final RateLimiter perIpHour;
    private final RateLimiter perIpDay;
    private final RateLimiter global;

    public ConverterLimits(StringRedisTemplate redisTemplate,
                           @Value("${app.converter.limit.per-ip-hour:3}") int perIpHourMax,
                           @Value("${app.converter.limit.per-ip-day:10}") int perIpDayMax,
                           @Value("${app.converter.limit.global-day:300}") int globalDayMax) {
        this.perIpHour = new RateLimiter(perIpHourMax, 3600, "convert-ip-hour", redisTemplate);
        this.perIpDay = new RateLimiter(perIpDayMax, 86400, "convert-ip-day", redisTemplate);
        this.global = new RateLimiter(globalDayMax, 86400, "convert-global-day", redisTemplate);
    }

    /** The three slots one conversion holds, so a conversion that fails through no fault of the
     *  visitor's can give them back. */
    public record Slots(String ip, String hour, String day, String all) {}

    /** Takes a slot in all three windows, or none: null when any window is full. */
    public Slots take(String ip) {
        String hour = perIpHour.reserve(ip);
        if (hour == null) return null;
        String day = perIpDay.reserve(ip);
        if (day == null) {
            perIpHour.refund(ip, hour);
            return null;
        }
        String all = global.reserve(GLOBAL_KEY);
        if (all == null) {
            perIpHour.refund(ip, hour);
            perIpDay.refund(ip, day);
            return null;
        }
        return new Slots(ip, hour, day, all);
    }

    public void giveBack(Slots slots) {
        if (slots == null) return;
        perIpHour.refund(slots.ip(), slots.hour());
        perIpDay.refund(slots.ip(), slots.day());
        global.refund(GLOBAL_KEY, slots.all());
    }
}
