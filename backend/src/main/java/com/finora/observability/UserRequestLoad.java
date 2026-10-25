package com.finora.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measures how many requests one signed-in user makes in a minute, and refuses nothing.
 *
 * <p>The question: every request limit in this app is keyed by IP, and signed-in endpoints have no
 * limit at all. A per-user limit would close that, but a limit set without data can lock real people
 * out (one dashboard load fires a burst of requests, and a mobile sync fires more). The request log
 * Railway keeps is about half an hour long and carries no user, so there was nothing to size a limit
 * from. This records the distribution; after a week of real traffic the limit can be set from it,
 * with headroom, instead of from a guess.
 *
 * <p><b>Privacy, as {@link ClientIpDiagnostics} does it:</b> no user id is ever stored, logged or
 * used as a metric tag. Per-user counts exist only in memory for the minute being counted, are
 * reduced to a number, and are dropped. What leaves this class is a distribution (how many
 * user-minutes had how many requests), the number of active users in a minute, and, for a minute
 * that crosses {@link #HEAVY_USER_MINUTE}, the route template of the request that crossed it and the
 * count, never who. That matches the promise the activity-day table makes: no per-person timeline.
 *
 * <p><b>What it exports</b> (tagged {@code portal=user|admin}, so admin tooling, which is bursty by
 * design, does not blur the customer numbers):
 * <ul>
 *   <li>{@code finora.user_load.requests_per_minute}: a histogram with buckets at
 *       {@link #BUCKETS}, one observation per active user per minute. "Fraction of user-minutes at
 *       or below N" is read straight from it, and the {@code _max} is the busiest user-minute;</li>
 *   <li>{@code finora.user_load.active_users}: distinct signed-in users seen in a minute;</li>
 *   <li>{@code finora.user_load.dropped}: users not counted because a minute already held
 *       {@link #MAX_USERS_PER_MINUTE}; should stay at zero;</li>
 * </ul>
 * and one log line every 15 minutes with the same numbers as counts, so the answer can be read from
 * Grafana or from the Railway log.
 *
 * <p><b>Accuracy.</b> A minute is counted into an in-memory window that is rotated by whichever
 * request first sees a new minute, and by a short scheduled tick when traffic is quiet. A request
 * that arrives at the instant of rotation can land in the window just flushed and go uncounted. That
 * loses a request, not a user, once in a very great while, which a measurement can afford and a
 * limit could not. Counts are per instance, which is all of production today (one replica).
 *
 * <p>Temporary: remove this, {@link UserRequestLoadInterceptor} and their registration once a
 * per-user limit has been chosen from the data, or keep only the metrics if they prove useful.
 */
@Component
public class UserRequestLoad {

    private static final Logger log = LoggerFactory.getLogger(UserRequestLoad.class);

    /** Histogram bucket edges, in requests per user per minute. Wide enough at the top to see
     *  whether anyone is anywhere near a limit that would be safe to impose. */
    static final double[] BUCKETS = {5, 10, 20, 30, 60, 120, 300, 600, 1200};

    /** A user-minute at or above this is one request every 100 ms for a whole minute: no screen of
     *  this app does that. Logged (route and count, no user) so the cause can be found. */
    static final int HEAVY_USER_MINUTE = 600;

    /** Bounds memory: a minute never holds more than this many distinct users. */
    static final int MAX_USERS_PER_MINUTE = 50_000;

    /** The count lines of the periodic summary: how many user-minutes were over each of these. */
    private static final int[] SUMMARY_THRESHOLDS = {30, 60, 120, 300, 600};

    private final MeterRegistry registry;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicReference<Window> window;
    private final Map<String, DistributionSummary> perMinute = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> activeUsers = new ConcurrentHashMap<>();
    private final Map<String, Totals> sinceLastSummary = new ConcurrentHashMap<>();
    private final Counter dropped;

    @Autowired
    public UserRequestLoad(MeterRegistry registry,
                           @Value("${app.diagnostics.user-load.enabled:true}") boolean enabled) {
        this(registry, Clock.systemUTC(), enabled);
    }

    UserRequestLoad(MeterRegistry registry, Clock clock, boolean enabled) {
        this.registry = registry;
        this.clock = clock;
        this.enabled = enabled;
        this.window = new AtomicReference<>(new Window(minuteNow()));
        this.dropped = Counter.builder("finora.user_load.dropped")
                .description("Users not counted because a minute already held the per-minute cap")
                .register(registry);
    }

    /**
     * Counts one request by a signed-in user. Never throws: a measurement must not affect a request.
     *
     * @param portal {@code user} or {@code admin}
     * @param route  the matched route template (for example {@code /api/v1/transactions/{id}}),
     *               used only if this request pushes a user-minute over {@link #HEAVY_USER_MINUTE}
     */
    public void record(UUID userId, String portal, String route) {
        if (!enabled || userId == null) return;
        try {
            long minute = minuteNow();
            Window current = window.get();
            if (current.minute != minute) {
                current = rotateTo(minute);
            }
            Counted counted = current.byUser.get(userId);
            if (counted == null) {
                if (current.byUser.size() >= MAX_USERS_PER_MINUTE) {
                    dropped.increment();
                    return;
                }
                counted = current.byUser.computeIfAbsent(userId, id -> new Counted(portalOrOther(portal)));
            }
            if (counted.requests.incrementAndGet() == HEAVY_USER_MINUTE) {
                // Exactly once per user-minute, because only the request that makes the count equal
                // the line logs. No user is named.
                log.info("A signed-in user reached {} requests in one minute (route of the request that crossed "
                        + "the line: {}). Counted only, nobody is limited.", HEAVY_USER_MINUTE, safeRoute(route));
            }
        } catch (RuntimeException e) {
            log.debug("User request load skipped a request: {}", e.getClass().getSimpleName());
        }
    }

    /** Moves to the window for {@code minute}, flushing the one that just ended. If another thread
     *  got there first, or the clock stepped backwards, the current window is used as it stands. */
    private Window rotateTo(long minute) {
        while (true) {
            Window current = window.get();
            if (current.minute >= minute) return current;
            Window fresh = new Window(minute);
            if (window.compareAndSet(current, fresh)) {
                flush(current);
                return fresh;
            }
        }
    }

    /** Called every 30 seconds so a quiet period still publishes the minute that has ended. */
    @Scheduled(fixedDelayString = "${app.diagnostics.user-load.flush-interval-ms:30000}",
            initialDelayString = "${app.diagnostics.user-load.flush-interval-ms:30000}")
    public void flushIfMinuteElapsed() {
        if (!enabled) return;
        try {
            long minute = minuteNow();
            if (window.get().minute != minute) rotateTo(minute);
        } catch (RuntimeException e) {
            log.debug("User request load could not flush: {}", e.getClass().getSimpleName());
        }
    }

    /** Publishes the minute being counted right now and starts a new one. For tests, which cannot
     *  wait a minute for a rotation. */
    public void flushNow() {
        Window ended = window.getAndSet(new Window(minuteNow()));
        flush(ended);
    }

    private void flush(Window ended) {
        Map<String, Integer> usersByPortal = new TreeMap<>();
        for (Counted counted : ended.byUser.values()) {
            int requests = counted.requests.get();
            if (requests <= 0) continue;
            perMinute.computeIfAbsent(counted.portal, this::newPerMinuteSummary).record(requests);
            sinceLastSummary.computeIfAbsent(counted.portal, p -> new Totals()).add(requests);
            usersByPortal.merge(counted.portal, 1, Integer::sum);
        }
        usersByPortal.forEach((portal, users) ->
                activeUsers.computeIfAbsent(portal, this::newActiveUsersSummary).record(users));
    }

    private DistributionSummary newPerMinuteSummary(String portal) {
        return DistributionSummary.builder("finora.user_load.requests_per_minute")
                .description("Requests one signed-in user made in one minute (one observation per active "
                        + "user per minute; no user is identified)")
                .baseUnit("requests")
                .serviceLevelObjectives(BUCKETS)
                .tag("portal", portal)
                .register(registry);
    }

    private DistributionSummary newActiveUsersSummary(String portal) {
        return DistributionSummary.builder("finora.user_load.active_users")
                .description("Distinct signed-in users that made a request in one minute")
                .baseUnit("users")
                .tag("portal", portal)
                .register(registry);
    }

    /** Logs the counts since the previous summary, then starts again. Counts only. */
    @Scheduled(fixedDelayString = "${app.diagnostics.user-load.summary-interval-ms:900000}",
            initialDelayString = "${app.diagnostics.user-load.summary-interval-ms:900000}")
    public void logSummary() {
        String summary = drainSummary();
        if (summary != null) {
            log.info("User request load (last interval, counts only, nobody identified): {}", summary);
        }
    }

    /** The counts since the last call, or null if no user-minute was counted. */
    String drainSummary() {
        Map<String, String> parts = new TreeMap<>();
        sinceLastSummary.forEach((portal, totals) -> {
            Totals drained = totals.drain();
            if (drained.userMinutes.sum() > 0) parts.put(portal, drained.describe());
        });
        return parts.isEmpty() ? null : parts.toString();
    }

    private long minuteNow() {
        return clock.millis() / 60_000;
    }

    private static String portalOrOther(String portal) {
        return "user".equals(portal) || "admin".equals(portal) ? portal : "other";
    }

    /** A route template is bounded and holds no ids; anything else is not logged as given. */
    private static String safeRoute(String route) {
        return route != null && route.startsWith("/") && route.length() <= 120 ? route : "unknown";
    }

    private static final class Window {
        final long minute;
        final ConcurrentHashMap<UUID, Counted> byUser = new ConcurrentHashMap<>();

        Window(long minute) {
            this.minute = minute;
        }
    }

    private static final class Counted {
        final String portal;
        final AtomicInteger requests = new AtomicInteger();

        Counted(String portal) {
            this.portal = portal;
        }
    }

    /** Running totals for the periodic summary line. */
    private static final class Totals {
        private final LongAdder userMinutes = new LongAdder();
        private final LongAdder requests = new LongAdder();
        private final LongAdder[] over = new LongAdder[SUMMARY_THRESHOLDS.length];
        private final AtomicInteger busiest = new AtomicInteger();

        Totals() {
            for (int i = 0; i < over.length; i++) over[i] = new LongAdder();
        }

        void add(int userMinuteRequests) {
            userMinutes.increment();
            requests.add(userMinuteRequests);
            busiest.accumulateAndGet(userMinuteRequests, Math::max);
            for (int i = 0; i < SUMMARY_THRESHOLDS.length; i++) {
                if (userMinuteRequests > SUMMARY_THRESHOLDS[i]) over[i].increment();
            }
        }

        /** A snapshot of the totals, with the live ones reset. */
        Totals drain() {
            Totals snapshot = new Totals();
            snapshot.userMinutes.add(userMinutes.sumThenReset());
            snapshot.requests.add(requests.sumThenReset());
            snapshot.busiest.set(busiest.getAndSet(0));
            for (int i = 0; i < over.length; i++) snapshot.over[i].add(over[i].sumThenReset());
            return snapshot;
        }

        String describe() {
            StringBuilder text = new StringBuilder("userMinutes=").append(userMinutes.sum())
                    .append(", requests=").append(requests.sum())
                    .append(", busiestUserMinute=").append(busiest.get());
            for (int i = 0; i < SUMMARY_THRESHOLDS.length; i++) {
                text.append(", over").append(SUMMARY_THRESHOLDS[i]).append('=').append(over[i].sum());
            }
            return text.toString();
        }
    }
}
