package com.finora.security;

import com.finora.entity.User;
import com.finora.repository.UserActivityDayRepository;
import com.finora.service.AuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records that a user was active today -- one {@code user_activity_days} row per user per calendar
 * day, so "how many days a month does someone actually use Fynora" has a real answer (see V249 for
 * why nothing else in the system could give one).
 *
 * <p>Counts only user-portal accounts. An admin working in the admin portal is not a user using the
 * product, and counting those requests would inflate exactly the number this exists to measure.
 * Requests without an authenticated principal never get here as a user -- the refresh call, login
 * and other public endpoints carry no bearer token -- so a session renewing itself in the background
 * is not mistaken for someone opening the app.
 *
 * <p>Writes at most once per user per day per backend instance: {@link #recordedDayByUser} remembers
 * who has already been recorded today, so every later request that day is a map lookup, not a
 * database round trip. The map is emptied when the day changes, so it holds at most one day's active
 * users. Across instances the insert itself is idempotent ({@code ON CONFLICT DO NOTHING}).
 *
 * <p>Runs in {@code preHandle} rather than {@code afterCompletion}: the request has been
 * authenticated by then, and it avoids the separate async-dispatch path {@code afterCompletion}
 * takes for streaming responses. A failure to record must never fail the user's request, so the
 * write is wrapped and logged; the row is a measurement, not a gate.
 */
@Component
public class UserActivityInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(UserActivityInterceptor.class);

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String USER_PORTAL_AUTHORITY = AuthorizationService.portalAuthority(User.SCOPE_USER);

    /** During a database outage every request would fail this write; one warning per minute is
     *  enough to show it is happening without burying the log lines that explain the outage. */
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000;

    private final UserActivityDayRepository userActivityDayRepository;
    private final Clock clock;
    private final Map<UUID, LocalDate> recordedDayByUser = new ConcurrentHashMap<>();
    private volatile LocalDate mapDay;
    private final AtomicLong lastFailureLoggedAt = new AtomicLong(0);

    @Autowired
    public UserActivityInterceptor(UserActivityDayRepository userActivityDayRepository,
                                   @Value("${app.platform.reporting-zone:Asia/Kolkata}") String reportingZoneId) {
        this(userActivityDayRepository, Clock.system(reportingZone(reportingZoneId)));
    }

    UserActivityInterceptor(UserActivityDayRepository userActivityDayRepository, Clock clock) {
        this.userActivityDayRepository = userActivityDayRepository;
        this.clock = clock;
    }

    /** Same zone, and same safe fallback, as the admin dashboard's platform-wide "today" tiles
     *  (AdminOperationalDashboardService) -- an unparseable value must not stop the app starting. */
    private static ZoneId reportingZone(String reportingZoneId) {
        try {
            return ZoneId.of(reportingZoneId);
        } catch (Exception e) {
            log.warn("app.platform.reporting-zone is not a recognized zone id ({}) -- falling back to "
                    + "Asia/Kolkata for user activity days.", reportingZoneId);
            return DEFAULT_ZONE;
        }
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        UUID userId = userPortalUserId();
        if (userId != null) {
            record(userId);
        }
        return true;
    }

    private void record(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(mapDay)) {
            synchronized (this) {
                if (!today.equals(mapDay)) {
                    recordedDayByUser.clear();
                    mapDay = today;
                }
            }
        }
        if (today.equals(recordedDayByUser.get(userId))) {
            return;
        }
        try {
            userActivityDayRepository.recordDay(userId, today);
            // Only after the write succeeded -- a failed write is retried on the user's next request.
            recordedDayByUser.put(userId, today);
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            long last = lastFailureLoggedAt.get();
            if (now - last >= FAILURE_LOG_INTERVAL_MS && lastFailureLoggedAt.compareAndSet(last, now)) {
                log.warn("Could not record today's activity row for a user; the request is unaffected. "
                        + "Further failures are suppressed for {}s.", FAILURE_LOG_INTERVAL_MS / 1000, e);
            }
        }
    }

    /** The authenticated user-portal account's id, or null for anonymous requests and admin-portal
     *  accounts. The principal's username is the user id (see CurrentUserDetailsService). */
    private static UUID userPortalUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails principal)) {
            return null;
        }
        boolean userPortal = authentication.getAuthorities().stream()
                .anyMatch(a -> USER_PORTAL_AUTHORITY.equals(a.getAuthority()));
        if (!userPortal) {
            return null;
        }
        try {
            return UUID.fromString(principal.getUsername());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
