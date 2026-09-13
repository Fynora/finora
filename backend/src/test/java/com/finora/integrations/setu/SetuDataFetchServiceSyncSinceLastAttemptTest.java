package com.finora.integrations.setu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** SetuDataFetchService.sync(link, from, to) itself is covered by SetuDataFetchServiceTest --
 *  this covers only the range computation syncSinceLastAttempt wraps around it, extracted from
 *  AccountAggregatorWebhookDispatcher's own data.ready case so the webhook path and the new
 *  reconciliation sweep can never compute it differently. */
class SetuDataFetchServiceSyncSinceLastAttemptTest {

    private SetuDataFetchService service;

    @BeforeEach
    void setUp() {
        // Only the seam this test exercises is real; everything sync() itself needs is mocked the
        // same way SetuDataFetchServiceTest already does, so this class stays focused on the range
        // math, not re-proving sync()'s own persistence behavior.
        service = new SetuDataFetchService(mock(SetuDataFetchGateway.class),
                mock(AccountAggregatorTransactionMapper.class), mock(com.finora.repository.TransactionRepository.class),
                mock(AccountAggregatorLinkRepository.class), mock(com.finora.service.EntitlementService.class),
                mock(com.finora.service.AuditService.class), mock(com.finora.service.ReconciliationService.class));
    }

    @Test
    void usesTheThreeMonthWindowWhenNeverSynced() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // lastSyncedAt left null.

        SetuDataFetchService spied = spy(service);
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.now().minusMonths(3)), eq(LocalDate.now()));
    }

    @Test
    void usesTheDayAfterLastSyncedAt() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));

        SetuDataFetchService spied = spy(service);
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 9, 2)), any());
    }

    @Test
    void skipsAndReturnsFalseWhenAlreadySyncedThroughToday() {
        // Regression case Plan 2's own review already found once (data.ready arriving same-day as
        // the initial backfill) -- from would invert past to. Preserved here, not just moved.
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.now().minusSeconds(60));

        SetuDataFetchService spied = spy(service);
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isFalse();
        verify(spied, never()).sync(any(), any(), any());
    }
}
