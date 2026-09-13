package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.repository.AccountRepository;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountAggregatorLinkLifecycleSweepServiceTest {

    @Test
    void pausesAnActiveLinkWhoseUserLostEntitlementAndRevertsPrimarySource() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink active = new AccountAggregatorLink();
        active.setUserId(userId);
        active.setAccountId(accountId);
        active.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(active));
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of());
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(false);
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(active.getStatus()).isEqualTo(AccountAggregatorLinkStatus.PAUSED);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
    }

    @Test
    void resumesAPausedLinkWhoseUserRegainedEntitlement() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink paused = new AccountAggregatorLink();
        paused.setUserId(userId);
        paused.setAccountId(accountId);
        paused.setStatus(AccountAggregatorLinkStatus.PAUSED);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of());
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of(paused));
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(true);
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.MANUAL);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(paused.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }

    @Test
    void expiresAnActiveLinkPastItsConsentExpiry() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        UUID userId = UUID.randomUUID();
        AccountAggregatorLink expired = new AccountAggregatorLink();
        expired.setUserId(userId);
        expired.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        expired.setConsentExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(expired));
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of());
        when(entitlementService.hasEntitlement(any(), any())).thenReturn(true); // entitled, just expired

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(expired.getStatus()).isEqualTo(AccountAggregatorLinkStatus.EXPIRED);
    }

    @Test
    void leavesAHealthyActiveLinkAlone() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        AccountAggregatorLink healthy = new AccountAggregatorLink();
        healthy.setUserId(UUID.randomUUID());
        healthy.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        healthy.setConsentExpiresAt(Instant.now().plus(300, ChronoUnit.DAYS));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(healthy));
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of());
        when(entitlementService.hasEntitlement(any(), any())).thenReturn(true);

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isZero();
        assertThat(healthy.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        verifyNoInteractions(accountRepository);
    }
}
