package com.finora.integrations.setu;

import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        // A real syncSinceLastAttempt(...) that actually fetches mutates the link's own
        // lastSyncedAt as a side effect (see SetuDataFetchService.sync) -- the sweep's own
        // before/after comparison relies on that, so the stub must simulate it too, not just
        // return true with no side effect.
        when(fetchService.syncSinceLastAttempt(stale)).thenAnswer(invocation -> {
            stale.setLastSyncedAt(Instant.now());
            return true;
        });

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

    // Bug found during Plan 6 Track A's own post-implementation review: SetuDataFetchService.sync's
    // entitlementService.hasEntitlement(...) call sits outside its own try/catch (that catch only
    // wraps the gateway.fetchTransactions/mapper.mapNew pair) -- an unexpected RuntimeException
    // there (a real infra hiccup querying entitlement state, not just a false return) propagates
    // uncaught through sync() and syncSinceLastAttempt(). Before this sweep existed, that risk was
    // always scoped to exactly one link per webhook delivery; this is the first place a single
    // link's uncaught failure could abort the whole batch, silently skipping every other stale link
    // still left in the loop that same tick -- the worst possible failure mode for code whose whole
    // purpose is being the fallback when something else already went wrong.
    @Test
    void oneLinkThrowingDoesNotStopTheRestOfTheSweep() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorLink failing = new AccountAggregatorLink();
        failing.setUserId(UUID.randomUUID());
        failing.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        AccountAggregatorLink healthy = new AccountAggregatorLink();
        healthy.setUserId(UUID.randomUUID());
        healthy.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(failing, healthy));
        when(staleness.isStale(failing)).thenReturn(true);
        when(staleness.isStale(healthy)).thenReturn(true);
        when(fetchService.syncSinceLastAttempt(failing)).thenThrow(new RuntimeException("entitlement lookup failed"));
        when(fetchService.syncSinceLastAttempt(healthy)).thenAnswer(invocation -> {
            healthy.setLastSyncedAt(Instant.now());
            return true;
        });

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isEqualTo(1);
        verify(fetchService).syncSinceLastAttempt(healthy);
        verify(auditService).record(eq(healthy.getUserId()), eq("ACCOUNT_AGGREGATOR_FORCE_FETCH_TRIGGERED"),
                eq("AccountAggregatorLink"), any());
    }

    // Bug found during Plan 6 Track A's own post-implementation review: SetuDataFetchService.sync
    // returns early WITHOUT touching lastSyncedAt/lastSyncStatus when the user isn't entitled (or
    // the gateway isn't configured) -- but syncSinceLastAttempt() still returns true, since its own
    // contract is only "was the computed date range non-empty," not "did sync() do real work." A
    // link that's ACTIVE-but-actually-lost-entitlement (a real, if narrow and self-correcting,
    // window: AccountAggregatorLinkLifecycleSweepService's own sweep hasn't caught up to flip it to
    // PAUSED yet) would get counted and audited as a real force-fetch every tick, forever, until
    // that other sweep eventually runs -- a misleading audit trail for exactly the kind of
    // regulated data-sharing feature the design spec says audit accuracy is "not optional" for.
    @Test
    void doesNotCountOrAuditANoOpFetchEvenWhenTheRangeWasNonEmpty() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorLink noLongerEntitled = new AccountAggregatorLink();
        noLongerEntitled.setUserId(UUID.randomUUID());
        noLongerEntitled.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        Instant staleSyncTime = Instant.now().minusSeconds(999_999);
        noLongerEntitled.setLastSyncedAt(staleSyncTime);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(noLongerEntitled));
        when(staleness.isStale(noLongerEntitled)).thenReturn(true);
        // Simulates sync()'s real no-op behavior: returns true (a range existed) but never touches
        // lastSyncedAt, exactly what happens when entitlementService.hasEntitlement(...) declines.
        when(fetchService.syncSinceLastAttempt(noLongerEntitled)).thenReturn(true);

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isZero();
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
