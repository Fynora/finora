package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Proves the force-fetch safety net through the real fetch/map/persist chain, not a mock standing
 * in for it -- same discipline as every other AA plan's own real-Postgres proof for a sweep that
 * mutates data on a schedule (Plan 4's AccountAggregatorGuardIT, Plan 5's own lifecycle-sweep IT).
 * SetuDataFetchGateway is the one seam still mocked -- there is no real Setu sandbox access in this
 * environment, same ceiling every AA plan has had since Plan 1.
 */
class AccountAggregatorReconciliationSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountAggregatorLinkRepository links;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountAggregatorReconciliationSweepService sweepService;
    @MockitoBean private SetuDataFetchGateway gateway;
    @MockitoBean private EntitlementService entitlementService;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("aa-reconciliation-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Reconciliation Sweep IT Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Linked Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountId = accountRepository.save(account).getId();

        when(entitlementService.hasEntitlement(eq(userId), eq(FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)))
                .thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
    }

    @Test
    void forceFetchesAStaleLinkAndPersistsARealTransaction() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // Stale by AccountAggregatorLinkStalenessService's default 3x24h=72h threshold -- see that
        // class's own doc comment for the multiplier. Verified against the real config (neither
        // application.yml nor application-test.yml overrides expected-cadence-hours) before writing
        // this fixture, not assumed.
        link.setLastSyncedAt(Instant.now().minus(100, ChronoUnit.HOURS));
        link.setLinkIdempotencyKey("aa-reconciliation-sweep-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        links.save(link);

        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of(
                        new SetuFiDataTransaction("txn-1", "DEBIT", new BigDecimal("450.00"),
                                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                                "Force-fetched transaction", new BigDecimal("1000.00"), null))));

        // >= 1, not == 1 -- this IT suite shares one Postgres instance across every test class
        // (see AbstractIntegrationTest), so sweep() may also pick up an unrelated stale ACTIVE link
        // left over from another test running in the same suite. The real proof is what happened to
        // THIS test's own fixtures, asserted below by account/link id, not the global count.
        int forced = sweepService.sweep();
        assertThat(forced).isGreaterThanOrEqualTo(1);

        List<Transaction> persisted = transactionRepository.findByUserIdAndAccountIdIn(userId, List.of(accountId));
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getAmount()).isEqualByComparingTo("450.00");
        assertThat(persisted.get(0).getSource()).isEqualTo(Transaction.Source.ACCOUNT_AGGREGATOR);

        AccountAggregatorLink reloaded = links.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);
    }

    @Test
    void leavesARecentlySyncedLinkUntouched() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // Truncated to microseconds -- last_synced_at is TIMESTAMPTZ (Postgres's default 6-digit/
        // microsecond precision), and on a Linux JVM Instant.now() carries true nanosecond
        // precision, which a real DB round trip loses (observed: expected ...312635578Z, reloaded
        // ...312636Z -- the driver rounds to the nearest microsecond, not truncates). Untruncated,
        // this assertion is a coin flip on the host JVM's own clock resolution: it happened to pass
        // on macOS (whose Instant.now() is already microsecond-granular) and failed on the Linux CI
        // runner the first time this IT actually ran against real Postgres -- PR checks run
        // unit-only tests, so this never executed for real until the push-to-main "full suite" job,
        // after #1444 had already merged. The sweep's own behavior was never wrong; only this
        // assertion's precision was.
        Instant recentSync = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        link.setLastSyncedAt(recentSync);
        link.setLinkIdempotencyKey("aa-reconciliation-sweep-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        links.save(link);

        // Not asserting the global forced count is zero -- same shared-Postgres reasoning as above;
        // another test's own stale link could legitimately force sweep() to do real work. This
        // link's own state not changing is the actual claim.
        sweepService.sweep();

        AccountAggregatorLink reloaded = links.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncedAt()).isEqualTo(recentSync);
        assertThat(transactionRepository.findByUserIdAndAccountIdIn(userId, List.of(accountId))).isEmpty();
    }
}
