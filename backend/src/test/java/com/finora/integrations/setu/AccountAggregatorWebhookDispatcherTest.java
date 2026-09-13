package com.finora.integrations.setu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountAggregatorWebhookDispatcherTest {

    private AccountAggregatorLinkRepository links;
    private com.finora.service.AuditService auditService;
    private AccountAggregatorIdentityResolutionService identityResolutionService;
    private SetuDataFetchService fetchService;
    private AccountAggregatorLinkManagementService linkManagementService;
    private AccountAggregatorWebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        links = mock(AccountAggregatorLinkRepository.class);
        auditService = mock(com.finora.service.AuditService.class);
        identityResolutionService = mock(AccountAggregatorIdentityResolutionService.class);
        fetchService = mock(SetuDataFetchService.class);
        linkManagementService = mock(AccountAggregatorLinkManagementService.class);
        dispatcher = new AccountAggregatorWebhookDispatcher(links, auditService,
                identityResolutionService, fetchService, linkManagementService);
    }

    @Test
    void consentRejectedMarksTheLinkRejected() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        when(links.findByConsentHandleId("consent-handle-1")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.rejected", "consent-handle-1");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REJECTED);
    }

    @Test
    void consentRevokedDelegatesToTheSharedLinkManagementService() {
        // The revoke transition itself (status change, account primarySource revert, audit) is
        // covered by AccountAggregatorLinkManagementServiceTest now that Task 4 extracted it out
        // of this dispatcher -- this only proves the dispatcher hands off to it with the right
        // audit-action provenance.
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByConsentHandleId("consent-handle-2")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.revoked", "consent-handle-2");

        verify(linkManagementService).revoke(link, "ACCOUNT_AGGREGATOR_CONSENT_REVOKED");
    }

    @Test
    void anUnknownConsentHandleIsIgnoredNotThrown() {
        when(links.findByConsentHandleId("unknown")).thenReturn(Optional.empty());

        dispatcher.dispatch("consent.revoked", "unknown");
        // No exception -- a webhook for a consent handle Fynora never recorded (or already
        // deleted) is logged and dropped, not a 500 that makes Setu retry-storm forever.
    }

    @Test
    void consentApprovedDelegatesToIdentityResolution() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        when(links.findByConsentHandleId("consent-handle-3")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.approved", "consent-handle-3");

        verify(identityResolutionService).resolveAndAttach(link);
    }

    // Plan 6 Track A: the range computation this case used to do inline moved to
    // SetuDataFetchService.syncSinceLastAttempt (shared with the new reconciliation sweep) -- these
    // now verify delegation, not the date math itself, which is covered by
    // SetuDataFetchServiceSyncSinceLastAttemptTest.
    @Test
    void dataReadyTriggersAFetchForAnActiveLink() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(UUID.randomUUID());
        link.setLastSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(links.findByConsentHandleId("consent-handle-1")).thenReturn(Optional.of(link));
        when(fetchService.syncSinceLastAttempt(link)).thenReturn(true);

        dispatcher.dispatch("data.ready", "consent-handle-1");

        verify(fetchService).syncSinceLastAttempt(link);
    }

    @Test
    void dataReadyIsIgnoredForALinkNotYetActive() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
        when(links.findByConsentHandleId("consent-handle-2")).thenReturn(Optional.of(link));

        dispatcher.dispatch("data.ready", "consent-handle-2");

        verifyNoInteractions(fetchService);
    }

    @Test
    void dataReadyDelegatesEvenWhenNeverSynced() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(UUID.randomUUID());
        // lastSyncedAt left null -- shouldn't happen in practice (backfill sets it, Task 6), but
        // the dispatcher must not NPE if it somehow does.
        when(links.findByConsentHandleId("consent-handle-3")).thenReturn(Optional.of(link));
        when(fetchService.syncSinceLastAttempt(link)).thenReturn(true);

        dispatcher.dispatch("data.ready", "consent-handle-3");

        verify(fetchService).syncSinceLastAttempt(link);
    }

    @Test
    void dataReadyLogsAndTakesNoFurtherActionWhenAlreadySyncedThroughToday() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(UUID.randomUUID());
        link.setLastSyncedAt(Instant.now().minusSeconds(60));
        when(links.findByConsentHandleId("consent-handle-4")).thenReturn(Optional.of(link));
        when(fetchService.syncSinceLastAttempt(link)).thenReturn(false);

        dispatcher.dispatch("data.ready", "consent-handle-4");

        verify(fetchService).syncSinceLastAttempt(link);
    }
}
