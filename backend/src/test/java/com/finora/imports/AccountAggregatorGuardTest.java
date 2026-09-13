package com.finora.imports;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStalenessService;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountAggregatorGuardTest {

    // Real instance, not a mock -- AccountAggregatorLinkStalenessService is a pure computation
    // over the link's own fields (Task 1), nothing to stub. 24h cadence -> 72h threshold.
    private final AccountAggregatorLinkStalenessService staleness =
            new AccountAggregatorLinkStalenessService(24);

    private AccountRepository accountRepositoryReturning(UUID userId, UUID accountId) {
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        return accountRepository;
    }

    @Test
    void refusesAnExistingAccountWhoseAaLinkIsActiveAndNotStale() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = accountRepositoryReturning(userId, accountId);

        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        // Freshly constructed -- lastSyncedAt null, updatedAt defaults to Instant.now() via the
        // entity's own field initializer, so this link is not stale.
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(new AccountAggregatorLink()));
        AuditService auditService = mock(AuditService.class);

        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        assertThatThrownBy(() -> guard.checkNotActivelySynced(userId, accountId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void allowsAnAccountWhoseAaLinkIsNotActive() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.MANUAL);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        AuditService auditService = mock(AuditService.class);

        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        guard.checkNotActivelySynced(userId, accountId); // does not throw
    }

    @Test
    void allowsManualImportWhenTheActiveLinkIsStaleAndAuditsIt() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = accountRepositoryReturning(userId, accountId);

        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setLastSyncedAt(Instant.now().minus(Duration.ofHours(96)));
        UUID linkId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(stale, "id", linkId);
        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(stale));
        AuditService auditService = mock(AuditService.class);

        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        guard.checkNotActivelySynced(userId, accountId); // does not throw -- the hatch is open

        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_OUTAGE_ESCAPE_HATCH_USED"),
                eq("AccountAggregatorLink"), eq(linkId));
    }

    @Test
    void theHatchClosesAgainOnceTheLinkResyncs() {
        // The auto-reclose guarantee this plan is built around: "computed, not persisted" only
        // means something if two calls against the same (mutated) link disagree. Same guard
        // instance, same repository mock, the underlying link object is mutated between calls the
        // way a real sync would mutate the row this guard re-reads on the next request.
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = accountRepositoryReturning(userId, accountId);

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setLastSyncedAt(Instant.now().minus(Duration.ofHours(96)));
        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(link));
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        guard.checkNotActivelySynced(userId, accountId); // stale -- hatch open, does not throw

        // A sync succeeds -- SetuDataFetchService.sync's own effect on the row this guard reads.
        link.setLastSyncedAt(Instant.now());

        assertThatThrownBy(() -> guard.checkNotActivelySynced(userId, accountId))
                .isInstanceOf(ApiException.class); // hatch closed again, no code path re-opens it
    }
}
