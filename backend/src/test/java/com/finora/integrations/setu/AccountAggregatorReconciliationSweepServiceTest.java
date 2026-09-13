package com.finora.integrations.setu;

import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorReconciliationSweepServiceTest {

    @Test
    void forceFetchesAStaleActiveLinkAndAuditsIt() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setUserId(userId);
        stale.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(stale));
        when(staleness.isStale(stale)).thenReturn(true);
        when(fetchService.syncSinceLastAttempt(stale)).thenReturn(true);

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isEqualTo(1);
        verify(fetchService).syncSinceLastAttempt(stale);
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_FORCE_FETCH_TRIGGERED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void leavesAHealthyActiveLinkAlone() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorLink healthy = new AccountAggregatorLink();
        healthy.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(healthy));
        when(staleness.isStale(healthy)).thenReturn(false);

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isZero();
        verifyNoInteractions(fetchService);
        verifyNoInteractions(auditService);
    }

    @Test
    void doesNotCountOrAuditWhenTheForceFetchFindsNothingToDo() {
        // A link the staleness threshold flags as overdue but whose computed range is somehow
        // already empty (e.g. a sync just landed in the gap between the staleness read and this
        // tick) -- syncSinceLastAttempt's own false return is the honest signal, not "found stale
        // therefore counted."
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorLink edgeCase = new AccountAggregatorLink();
        edgeCase.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(edgeCase));
        when(staleness.isStale(edgeCase)).thenReturn(true);
        when(fetchService.syncSinceLastAttempt(edgeCase)).thenReturn(false);

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isZero();
        verifyNoInteractions(auditService);
    }
}
