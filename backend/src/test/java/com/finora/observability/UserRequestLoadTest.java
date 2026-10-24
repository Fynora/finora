package com.finora.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserRequestLoadTest {

    private SimpleMeterRegistry registry;
    private MutableClock clock;
    private UserRequestLoad load;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-10-24T10:00:10Z"));
        load = new UserRequestLoad(registry, clock, true);
        logger = (Logger) LoggerFactory.getLogger(UserRequestLoad.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private DistributionSummary perMinute(String portal) {
        return registry.find("finora.user_load.requests_per_minute").tag("portal", portal).summary();
    }

    private DistributionSummary active(String portal) {
        return registry.find("finora.user_load.active_users").tag("portal", portal).summary();
    }

    private void record(UUID user, String portal, int times) {
        for (int i = 0; i < times; i++) load.record(user, portal, "/api/v1/dashboard");
    }

    @Test
    void oneObservationPerActiveUserPerMinute_carryingThatUsersRequestCount() {
        record(UUID.randomUUID(), "user", 3);
        record(UUID.randomUUID(), "user", 1);

        load.flushNow();

        DistributionSummary summary = perMinute("user");
        assertThat(summary.count()).as("two users were active").isEqualTo(2);
        assertThat(summary.totalAmount()).as("4 requests in all").isEqualTo(4.0);
        assertThat(summary.max()).as("the busiest user-minute").isEqualTo(3.0);
        assertThat(active("user").max()).as("two distinct users in that minute").isEqualTo(2.0);
    }

    @Test
    void adminAndCustomerTrafficAreMeasuredSeparately() {
        record(UUID.randomUUID(), "user", 2);
        record(UUID.randomUUID(), "admin", 40);

        load.flushNow();

        assertThat(perMinute("user").max()).isEqualTo(2.0);
        assertThat(perMinute("admin").max()).isEqualTo(40.0);
    }

    @Test
    void aPortalNameThatIsNotUserOrAdminIsFiledUnderOther() {
        record(UUID.randomUUID(), "someone-elses-name", 1);

        load.flushNow();

        assertThat(perMinute("other").count()).isEqualTo(1);
        assertThat(registry.find("finora.user_load.requests_per_minute").tag("portal", "someone-elses-name").summary())
                .as("an unbounded tag value would be unbounded metric cardinality")
                .isNull();
    }

    @Test
    void theMinuteIsPublishedOnceTheNextMinuteBegins_withoutAnyoneCallingFlush() {
        UUID user = UUID.randomUUID();
        record(user, "user", 5);
        assertThat(perMinute("user")).as("nothing is published while the minute is still open").isNull();

        clock.advanceSeconds(60);
        record(user, "user", 2);

        assertThat(perMinute("user").count()).isEqualTo(1);
        assertThat(perMinute("user").max()).as("the first minute's 5, not the second's 2").isEqualTo(5.0);
    }

    @Test
    void theScheduledTickPublishesAQuietMinute() {
        record(UUID.randomUUID(), "user", 4);
        clock.advanceSeconds(75);

        load.flushIfMinuteElapsed();

        assertThat(perMinute("user").max()).isEqualTo(4.0);
    }

    @Test
    void theTickDoesNothingWhileTheMinuteIsStillOpen() {
        record(UUID.randomUUID(), "user", 4);

        load.flushIfMinuteElapsed();

        assertThat(perMinute("user")).isNull();
    }

    @Test
    void aUserActiveInTwoMinutesIsTwoObservationsNotOne() {
        UUID user = UUID.randomUUID();
        record(user, "user", 3);
        clock.advanceSeconds(60);
        record(user, "user", 3);
        load.flushNow();

        assertThat(perMinute("user").count()).isEqualTo(2);
        assertThat(perMinute("user").totalAmount()).isEqualTo(6.0);
    }

    @Test
    void theHistogramBucketsLetYouReadOffHowManyUserMinutesStayedUnderAGivenRate() {
        record(UUID.randomUUID(), "user", 4);   // <= 5
        record(UUID.randomUUID(), "user", 25);  // <= 30
        record(UUID.randomUUID(), "user", 700); // <= 1200
        load.flushNow();

        List<CountAtBucket> buckets = List.of(perMinute("user").takeSnapshot().histogramCounts());
        assertThat(countAtOrBelow(buckets, 5)).isEqualTo(1);
        assertThat(countAtOrBelow(buckets, 30)).isEqualTo(2);
        assertThat(countAtOrBelow(buckets, 600)).isEqualTo(2);
        assertThat(countAtOrBelow(buckets, 1200)).isEqualTo(3);
    }

    private static double countAtOrBelow(List<CountAtBucket> buckets, double edge) {
        return buckets.stream().filter(b -> b.bucket() == edge).findFirst().orElseThrow().count();
    }

    @Test
    void nothingIsRecordedWhenSwitchedOff() {
        UserRequestLoad off = new UserRequestLoad(registry, clock, false);

        off.record(UUID.randomUUID(), "user", "/api/v1/dashboard");
        off.flushNow();
        off.flushIfMinuteElapsed();

        assertThat(registry.find("finora.user_load.requests_per_minute").summary()).isNull();
    }

    @Test
    void aRequestWithNoUserIsIgnoredAndDoesNotThrow() {
        load.record(null, "user", "/api/v1/dashboard");
        load.flushNow();

        assertThat(registry.find("finora.user_load.requests_per_minute").summary()).isNull();
    }

    @Test
    void aMinuteHoldsAtMostFiftyThousandUsers_andTheOverflowIsCounted() {
        for (int i = 0; i < UserRequestLoad.MAX_USERS_PER_MINUTE; i++) load.record(UUID.randomUUID(), "user", "/api/x");
        UUID late = UUID.randomUUID();
        load.record(late, "user", "/api/x");
        load.record(late, "user", "/api/x");

        load.flushNow();

        assertThat(perMinute("user").count()).isEqualTo(UserRequestLoad.MAX_USERS_PER_MINUTE);
        assertThat(registry.find("finora.user_load.dropped").counter().count())
                .as("each request of an uncounted user is counted as dropped")
                .isEqualTo(2.0);
    }

    @Test
    void aUserWhoWasAlreadyCountedKeepsBeingCountedWhenTheMinuteIsFull() {
        UUID early = UUID.randomUUID();
        load.record(early, "user", "/api/x");
        for (int i = 0; i < UserRequestLoad.MAX_USERS_PER_MINUTE; i++) load.record(UUID.randomUUID(), "user", "/api/x");

        load.record(early, "user", "/api/x");
        load.flushNow();

        assertThat(perMinute("user").max()).as("the early user's two requests").isEqualTo(2.0);
    }

    @Test
    void reachingSixHundredRequestsInAMinuteIsLoggedOnce_withTheRouteAndWithoutTheUser() {
        UUID user = UUID.randomUUID();

        for (int i = 0; i < 1500; i++) load.record(user, "user", "/api/v1/transactions/{id}");

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("requests in one minute")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("/api/v1/transactions/{id}").doesNotContain(user.toString());
    }

    @Test
    void justUnderTheLineLogsNothing() {
        UUID user = UUID.randomUUID();

        for (int i = 0; i < UserRequestLoad.HEAVY_USER_MINUTE - 1; i++) load.record(user, "user", "/api/x");

        assertThat(logs.list).isEmpty();
    }

    @Test
    void aRouteThatIsNotATemplateIsNotLoggedAsGiven() {
        UUID user = UUID.randomUUID();

        for (int i = 0; i < UserRequestLoad.HEAVY_USER_MINUTE; i++) load.record(user, "user", "someone@example.com");

        assertThat(logs.list.get(0).getFormattedMessage()).contains("unknown").doesNotContain("someone@example.com");
    }

    @Test
    void theSummaryGivesCountsOnly_thenStartsAgain() {
        UUID busy = UUID.randomUUID();
        record(busy, "user", 130);
        record(UUID.randomUUID(), "user", 2);
        load.flushNow();

        String summary = load.drainSummary();

        assertThat(summary).contains("userMinutes=2", "requests=132", "busiestUserMinute=130", "over30=1", "over120=1", "over300=0");
        assertThat(summary).doesNotContain(busy.toString());
        assertThat(load.drainSummary()).as("drained").isNull();
    }

    @Test
    void theSummaryIsEmptyWhenNobodyWasActive() {
        assertThat(load.drainSummary()).isNull();
    }

    @Test
    void aClockThatStepsBackwardsDoesNotLoseOrDoubleCountTheOpenMinute() {
        UUID user = UUID.randomUUID();
        record(user, "user", 2);

        clock.advanceSeconds(-300);
        record(user, "user", 2);
        load.flushNow();

        assertThat(perMinute("user").count()).isEqualTo(1);
        assertThat(perMinute("user").max()).isEqualTo(4.0);
    }

    @Test
    void manyThreadsCountEveryRequest() throws Exception {
        UUID user = UUID.randomUUID();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) load.record(user, "user", "/api/x");
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();

        load.flushNow();

        assertThat(perMinute("user").max()).isEqualTo(4000.0);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
