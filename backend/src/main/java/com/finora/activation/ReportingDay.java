package com.finora.activation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Today's date in the platform reporting zone ({@code app.platform.reporting-zone}, default
 * Asia/Kolkata) -- the day the activation records are filed under, as {@code user_activity_days}
 * is. Not UTC, which would split an Indian evening across two days at 05:30 IST.
 */
@Component
public class ReportingDay {

    private static final Logger log = LoggerFactory.getLogger(ReportingDay.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    private final Clock clock;

    @Autowired
    public ReportingDay(@Value("${app.platform.reporting-zone:Asia/Kolkata}") String zone) {
        this(Clock.system(parse(zone)));
    }

    ReportingDay(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The current instant, from the same clock, so a test that pins the day pins the instant too. */
    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    /** Same safe fallback as UserActivityInterceptor: an unparseable value must not stop the app starting. */
    private static ZoneId parse(String zone) {
        try {
            return ZoneId.of(zone);
        } catch (Exception e) {
            log.warn("app.platform.reporting-zone is not a recognized zone id ({}) -- falling back to "
                    + "Asia/Kolkata for activation records.", zone);
            return DEFAULT_ZONE;
        }
    }
}
