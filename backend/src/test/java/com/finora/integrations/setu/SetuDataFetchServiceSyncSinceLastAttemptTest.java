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
 *  real wall clock only reaches at certain hours in certain zones. */
class SetuDataFetchServiceSyncSinceLastAttemptTest {

    private SetuDataFetchService serviceWithClock(Clock clock) {
        return new SetuDataFetchService(mock(SetuDataFetchGateway.class),
                mock(AccountAggregatorTransactionMapper.class), mock(com.finora.repository.TransactionRepository.class),
                mock(AccountAggregatorLinkRepository.class), mock(com.finora.service.EntitlementService.class),
                mock(com.finora.service.AuditService.class), mock(com.finora.service.ReconciliationService.class),
                clock);
    }

    @Test
    void usesTheThreeMonthWindowWhenNeverSynced() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // lastSyncedAt left null.

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 3, 15)), eq(LocalDate.of(2026, 6, 15)));
    }

    @Test
    void usesTheDayAfterLastSyncedAt() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 9, 2)), eq(LocalDate.of(2026, 9, 10)));
    }

    @Test
    void skipsAndReturnsFalseWhenAlreadySyncedThroughToday() {
        // Regression case Plan 2's own review already found once (data.ready arriving same-day as
        // the initial backfill) -- from would invert past to. Preserved here, not just moved.
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.parse("2026-09-10T09:59:00Z"));

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isFalse();
        verify(spied, never()).sync(any(), any(), any());
    }

    @Test
    void doesNotInvertTheRangeAcrossTheUtcDayBoundaryRegardlessOfTheHostsLocalZone() {
        // The bug this pins: `to` used to be LocalDate.now() (the JVM's default zone) while `from`
        // was always computed in UTC. For a host running in IST (UTC+5:30), there is a ~5.5-hour
        // window each day -- the last few minutes of the UTC day here -- where the JVM's local
        // calendar date has already rolled over to the next day but UTC has not. `lastSyncedAt` one
        // minute before the fixed instant means "already synced today" in every zone; the range
        // must therefore come out empty (from.isAfter(to)) no matter what zone the host JVM
        // defaults to, because syncSinceLastAttempt no longer consults the JVM's default zone at
        // all -- both endpoints are pinned to the same injected UTC clock.
        Instant justBeforeUtcMidnight = Instant.parse("2026-09-13T23:59:00Z");
        Clock clock = Clock.fixed(justBeforeUtcMidnight, ZoneOffset.UTC);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(justBeforeUtcMidnight.minusSeconds(60));

        SetuDataFetchService spied = spy(serviceWithClock(clock));
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isFalse();
        verify(spied, never()).sync(any(), any(), any());
    }
}
