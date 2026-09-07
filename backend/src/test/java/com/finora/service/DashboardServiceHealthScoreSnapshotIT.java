package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.DashboardSummaryDto;
import com.finora.entity.Account;
import com.finora.entity.HealthScoreSnapshot;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.HealthScoreSnapshotRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DashboardServiceTest} proves the health-score snapshot logic against a mocked
 * {@link HealthScoreSnapshotRepository}; this proves, against a real Postgres, the one thing a
 * mock-only suite cannot: that a snapshot written mid-request by {@code upsertForMonth}'s
 * {@code REQUIRES_NEW} transaction is actually visible to the SAME request's own later read-only
 * queries (the sparkline/delta lookups) -- relying on Postgres's READ COMMITTED default (each
 * statement in a transaction sees everything committed before that statement started, even a
 * sibling sub-transaction that committed after the outer transaction began). That visibility
 * assumption is exactly the kind of thing "reasoned about" is not the same as "verified" for.
 */
class DashboardServiceHealthScoreSnapshotIT extends AbstractIntegrationTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private HealthScoreSnapshotRepository healthScoreSnapshotRepository;

    private UUID persistUser() {
        User user = new User();
        user.setEmail("health-score-e2e-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Health Score E2E Test");
        return userRepository.save(user).getId();
    }

    private UUID persistSavingsAccount(UUID userId, BigDecimal balance) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(balance);
        account.setAccountHolderName("Health Score E2E Test");
        return accountRepository.save(account).getId();
    }

    private void persistTransaction(UUID userId, UUID accountId, BigDecimal amount, Transaction.Type type, LocalDate date) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setAmount(amount);
        t.setTxnType(type);
        t.setTxnDate(date);
        t.setDescription("Health score e2e fixture");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        transactionRepository.save(t);
    }

    /** Same zone DashboardService itself falls back to for a user with no timezone set. */
    private LocalDate today() {
        return LocalDate.now(UserZone.DEFAULT);
    }

    @Test
    void summarize_persistsASnapshotVisibleWithinTheSameRequest_andToASubsequentRead() {
        UUID userId = persistUser();
        UUID accountId = persistSavingsAccount(userId, new BigDecimal("100000"));
        LocalDate today = today();
        persistTransaction(userId, accountId, new BigDecimal("50000"), Transaction.Type.INCOME, today);
        for (int i = 0; i < 10; i++) {
            persistTransaction(userId, accountId, new BigDecimal("1000"), Transaction.Type.EXPENSE, today);
        }

        DashboardSummaryDto result = dashboardService.summarize(userId);

        assertThat(result.healthScoreAvailable()).isTrue();
        String currentYearMonth = YearMonth.now(UserZone.DEFAULT).toString();

        // Visible to a fresh, independent read after the request completes -- proves the write
        // really committed, not just that the in-memory DTO looks right.
        List<HealthScoreSnapshot> snapshots = healthScoreSnapshotRepository.findTop6ByUserIdOrderByYearMonthDesc(userId);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).getYearMonth()).isEqualTo(currentYearMonth);
        assertThat(snapshots.get(0).getOverallScore()).isEqualTo(result.healthScore());

        // Visible to the SAME request's own sparkline read, issued moments after the REQUIRES_NEW
        // upsert committed, inside the still-open outer read-only transaction -- the actual
        // cross-transaction visibility claim this test exists to verify, not just assert.
        assertThat(result.healthSparkline()).hasSize(1);
        assertThat(result.healthSparkline().get(0).yearMonth()).isEqualTo(currentYearMonth);
        assertThat(result.healthSparkline().get(0).score()).isEqualTo(result.healthScore());
    }

    @Test
    void summarize_computesARealDeltaAgainstAPreSeededPriorSnapshot() {
        UUID userId = persistUser();
        UUID accountId = persistSavingsAccount(userId, new BigDecimal("100000"));
        LocalDate today = today();
        persistTransaction(userId, accountId, new BigDecimal("50000"), Transaction.Type.INCOME, today);
        for (int i = 0; i < 10; i++) {
            persistTransaction(userId, accountId, new BigDecimal("1000"), Transaction.Type.EXPENSE, today);
        }

        // A prior snapshot far enough in the past that it is always strictly before "this month",
        // regardless of when this test actually runs.
        healthScoreSnapshotRepository.upsertForMonth(userId, "2020-01", 40, "Fair", 40, 40, 40, 40, 40);

        DashboardSummaryDto result = dashboardService.summarize(userId);

        assertThat(result.healthScoreDeltaVsLastMonth()).isEqualTo(result.healthScore() - 40);
    }

    @Test
    void summarize_neverPersistsASnapshot_whenTheScoreIsUnavailable() {
        UUID userId = persistUser();
        persistSavingsAccount(userId, new BigDecimal("100000"));
        // No transactions at all -- below MIN_TRANSACTIONS_FOR_HEALTH_SCORE.

        DashboardSummaryDto result = dashboardService.summarize(userId);

        assertThat(result.healthScoreAvailable()).isFalse();
        assertThat(healthScoreSnapshotRepository.findTop6ByUserIdOrderByYearMonthDesc(userId)).isEmpty();
    }
}
