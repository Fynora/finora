package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** SetuDataFetchService.sync(link, from, to) itself is covered by SetuDataFetchServiceTest --
 *  this covers only the range computation syncSinceLastAttempt wraps around it, extracted from
 *  AccountAggregatorWebhookDispatcher's own data.ready case so the webhook path and the new
 *  reconciliation sweep can never compute it differently.
 *
 *  <p>Every test here pins "now" via an injected {@link Clock} rather than the real one, so the
 *  range math is exercised deterministically -- including at the exact UTC-day boundary, which the
 *  real wall clock only reaches at certain hours in certain zones (#1448's own history for why:
 *  a wall-clock-dependent version of this test file passed for months until it happened to run
 *  during that exact window).
 *
 *  <p>Plan 6, Track B widened the computed range to never be narrower than a 14-day sliding
 *  window -- see SetuDataFetchService.syncSinceLastAttempt's own doc comment. That retired the old
 *  "already synced through today" skip case entirely: since the window floor is always &lt;= today
 *  for any non-negative window, `from` can never land after `to` under normal configuration any
 *  more. It also means the window floor DOMINATES for any link synced more recently than the
 *  window itself -- the incremental range only wins back once a link has gone unsynced for LONGER
 *  than the window (see usesTheDayAfterLastSyncedAtWhenItIsOlderThanTheSlidingWindow below). */
class SetuDataFetchServiceSyncSinceLastAttemptTest {

    private static final int SLIDING_WINDOW_DAYS = 14;

    private SetuDataFetchService serviceWithClock(Clock clock) {
        return new SetuDataFetchService(mock(SetuDataFetchGateway.class),
                mock(AccountAggregatorTransactionDiffService.class), mock(com.finora.repository.TransactionRepository.class),
                mock(AccountAggregatorLinkRepository.class), mock(com.finora.service.EntitlementService.class),
                mock(com.finora.service.AuditService.class), mock(com.finora.service.ReconciliationService.class),
                SLIDING_WINDOW_DAYS, clock);
    }

    @Test
    void usesTheThreeMonthWindowWhenNeverSynced() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // lastSyncedAt left null.

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        // 3 months is far wider than the 14-day sliding window, so the window floor never binds
        // here -- the incremental (never-synced) range wins, unchanged from before Track B.
        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 3, 15)), eq(LocalDate.of(2026, 6, 15)));
    }

    @Test
    void usesTheDayAfterLastSyncedAtWhenItIsOlderThanTheSlidingWindow() {
        // The window floor (today - 14 days) is only NARROWER than the incremental range when the
        // link has gone unsynced for LONGER than the window -- for anything more recent, the window
        // floor is further back in time than the incremental start and so dominates (see
        // neverFetchesLessThanTheSlidingWindowEvenWhenRecentlySynced below). 20 days is comfortably
        // beyond the 14-day window, so the true incremental gap (wider than the window) must win.
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.parse("2026-08-21T10:00:00Z"));

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 8, 22)), eq(LocalDate.of(2026, 9, 10)));
    }

    @Test
    void neverFetchesLessThanTheSlidingWindowEvenWhenRecentlySynced() {
        // Plan 6, Track B's own behavior change: a link synced moments ago used to make
        // syncSinceLastAttempt skip entirely (see the retired skipsAndReturnsFalseWhenAlready
        // SyncedThroughToday, noted below this class's tests) -- now it still re-fetches the full
        // trailing window, since a correction landing inside that window must remain visible even
        // when nothing was incrementally new.
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.parse("2026-09-10T09:00:00Z"));

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 8, 27)), eq(LocalDate.of(2026, 9, 10)));
    }

    @Test
    void doesNotInvertTheRangeAcrossTheUtcDayBoundaryRegardlessOfTheHostsLocalZone() {
        // The bug this pins (#1448): `to` used to be LocalDate.now() (the JVM's default zone) while
        // `from`/the window floor derive from lastSyncedAt's explicit UTC conversion. For a host
        // running in IST (UTC+5:30), there is a ~5.5-hour window each day -- the last few minutes of
        // the UTC day here -- where the JVM's local calendar date has already rolled over to the
        // next day but UTC has not. If `to` used the wrong zone, it would read one day later here
        // (2026-09-14 instead of 2026-09-13), shifting the computed window floor by exactly one day
        // too -- this test's exact-date assertion below would catch that regardless of which of the
        // two dates drifted, without needing the (now unreachable, see below) skip case as the
        // vehicle to prove it.
        Instant justBeforeUtcMidnight = Instant.parse("2026-09-13T23:59:00Z");
        Clock clock = Clock.fixed(justBeforeUtcMidnight, ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(justBeforeUtcMidnight.minusSeconds(60));

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 8, 30)), eq(LocalDate.of(2026, 9, 13)));
    }

    // skipsAndReturnsFalseWhenAlreadySyncedThroughToday was retired here, not silently deleted:
    // Plan 6, Track B's sliding-window floor (see the class doc comment above) makes the
    // "already synced through today" skip unreachable under any non-negative window -- the window
    // floor is always <= today, so `from` can never land after `to` any more. The regression case
    // this test used to guard (Plan 2's own review: data.ready arriving same-day as the initial
    // backfill inverting from/to) is superseded by design: the diff service's own no-op handling
    // (AccountAggregatorTransactionDiffServiceTest#sameTxnIdSameValuesIsANoOp) is what now keeps a
    // same-day repeat sync safe, not a skip. neverFetchesLessThanTheSlidingWindowEvenWhenRecentlySynced
    // above proves the new behavior directly, and
    // doesNotInvertTheRangeAcrossTheUtcDayBoundaryRegardlessOfTheHostsLocalZone above still proves
    // #1448's own zone-consistency regression is guarded, just via the window-floor path instead of
    // the (now unreachable) skip path.
}
