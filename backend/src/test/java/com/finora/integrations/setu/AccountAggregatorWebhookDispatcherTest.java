package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
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
    private AccountRepository accountRepository;
    private com.finora.service.AuditService auditService;
    private AccountAggregatorIdentityResolutionService identityResolutionService;
    private SetuDataFetchService fetchService;
    private AccountAggregatorWebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        links = mock(AccountAggregatorLinkRepository.class);
        accountRepository = mock(AccountRepository.class);
        auditService = mock(com.finora.service.AuditService.class);
        identityResolutionService = mock(AccountAggregatorIdentityResolutionService.class);
        fetchService = mock(SetuDataFetchService.class);
        dispatcher = new AccountAggregatorWebhookDispatcher(links, accountRepository, auditService,
                identityResolutionService, fetchService);
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
    void consentRevokedMarksTheLinkRevoked() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByConsentHandleId("consent-handle-2")).thenReturn(Optional.of(link));

        dispatcher.dispatch("consent.revoked", "consent-handle-2");

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REVOKED);
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

    @Test
    void consentRevokedRevertsTheLinkedAccountToManual() {
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID());
        link.setAccountId(accountId);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByConsentHandleId("consent-handle-4")).thenReturn(Optional.of(link));

        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        dispatcher.dispatch("consent.revoked", "consent-handle-4");

        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
        verify(accountRepository).save(account);
    }

    @Test
    void dataReadyTriggersAFetchForAnActiveLink() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(UUID.randomUUID());
        link.setLastSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(links.findByConsentHandleId("consent-handle-1")).thenReturn(Optional.of(link));

        dispatcher.dispatch("data.ready", "consent-handle-1");

        verify(fetchService).sync(eq(link), eq(LocalDate.of(2026, 9, 2)), any());
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
    void dataReadyOnFirstSyncUsesTheThreeMonthWindow() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(UUID.randomUUID());
        // lastSyncedAt left null -- shouldn't happen in practice (backfill sets it, Task 6), but
        // the dispatcher must not NPE if it somehow does.
        when(links.findByConsentHandleId("consent-handle-3")).thenReturn(Optional.of(link));

        dispatcher.dispatch("data.ready", "consent-handle-3");

        verify(fetchService).sync(eq(link), eq(LocalDate.now().minusMonths(3)), any());
    }
}
