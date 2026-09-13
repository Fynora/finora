package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Plan 6, Track B. Proves the three-way diff through the real fetch/diff/persist chain -- not a
 * mock standing in for the diff service -- against real Postgres. Same discipline every AA plan's
 * own sweep/sync IT has required in this session (Plan 4's AccountAggregatorGuardIT, Plan 5's
 * lifecycle-sweep IT, Track A's own AccountAggregatorReconciliationSweepServiceIT): this is the
 * first AA test proving a Transaction row is DELIBERATELY LEFT UNMUTATED while still being flagged,
 * which only a real save/findById round trip actually proves -- a mock can assert the intent, not
 * the outcome. SetuDataFetchGateway is the one seam still mocked -- no real Setu sandbox access in
 * this environment, same ceiling every AA plan has had since Plan 1.
 */
class AccountAggregatorTransactionDiffServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountAggregatorLinkRepository links;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private SetuDataFetchService fetchService;
    @MockitoBean private SetuDataFetchGateway gateway;
    @MockitoBean private EntitlementService entitlementService;

    private UUID userId;
    private UUID accountId;
    private AccountAggregatorLink link;

    private static final LocalDate FROM = LocalDate.now().minusDays(10);
    private static final LocalDate TO = LocalDate.now();

    @BeforeEach
    void setUp() {
        com.finora.entity.User user = new com.finora.entity.User();
        user.setEmail("aa-diff-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Diff IT Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Linked Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountId = accountRepository.save(account).getId();

        link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLinkIdempotencyKey("aa-diff-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        link = links.save(link);

        when(entitlementService.hasEntitlement(eq(userId), eq(FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)))
                .thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
    }

    private List<Transaction> ownTransactions() {
        return transactionRepository.findByAccountIdAndSourceAndTxnDateBetween(
                accountId, Transaction.Source.ACCOUNT_AGGREGATOR, FROM, TO);
    }

    @Test
    void aNewTransactionPersistsExactlyAsBefore() {
        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), eq(FROM), eq(TO)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of(
                        new SetuFiDataTransaction("txn-new", "DEBIT", new BigDecimal("450.00"),
                                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                                "Coffee shop", new BigDecimal("1000.00"), null))));

        fetchService.sync(link, FROM, TO);

        List<Transaction> persisted = ownTransactions();
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getAmount()).isEqualByComparingTo("450.00");
        assertThat(persisted.get(0).isPendingBankCorrection()).isFalse();
    }

    @Test
    void aCorrectedTransactionIsFlaggedNotOverwritten() {
        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), eq(FROM), eq(TO)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of(
                        new SetuFiDataTransaction("txn-corrected", "DEBIT", new BigDecimal("500.00"),
                                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                                "Pending charge", new BigDecimal("1000.00"), null))));
        fetchService.sync(link, FROM, TO);
        UUID persistedId = ownTransactions().get(0).getId();

        // Second fetch: the bank corrects the amount for the SAME txnId.
        reset(gateway);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), eq(FROM), eq(TO)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of(
                        new SetuFiDataTransaction("txn-corrected", "DEBIT", new BigDecimal("700.00"),
                                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                                "Posted charge", new BigDecimal("1000.00"), null))));
        fetchService.sync(link, FROM, TO);

        List<Transaction> persisted = ownTransactions();
        assertThat(persisted).hasSize(1); // still one row -- never a silent second insert either
        Transaction reloaded = transactionRepository.findById(persistedId).orElseThrow();
        // The single highest-value assertion in this whole plan: the row's OWN value is untouched.
        assertThat(reloaded.getAmount()).isEqualByComparingTo("500.00");
        assertThat(reloaded.getDescription()).isEqualTo("Pending charge");
        assertThat(reloaded.isPendingBankCorrection()).isTrue();

        List<AuditLog> audit = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(persistedId);
        assertThat(audit).extracting(AuditLog::getAction)
                .contains("ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED");
    }

    @Test
    void aVanishedTransactionIsFlaggedAsMissing() {
        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), eq(FROM), eq(TO)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of(
                        new SetuFiDataTransaction("txn-vanished", "DEBIT", new BigDecimal("250.00"),
                                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                                "Pre-auth hold", new BigDecimal("1000.00"), null))));
        fetchService.sync(link, FROM, TO);
        UUID persistedId = ownTransactions().get(0).getId();

        // Second fetch: the pre-auth never posted -- Setu no longer reports it at all.
        reset(gateway);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), eq(FROM), eq(TO)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of()));
        fetchService.sync(link, FROM, TO);

        Transaction reloaded = transactionRepository.findById(persistedId).orElseThrow();
        assertThat(reloaded.getAmount()).isEqualByComparingTo("250.00"); // never deleted, never mutated
        assertThat(reloaded.isPendingBankCorrection()).isTrue();

        List<AuditLog> audit = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(persistedId);
        assertThat(audit).extracting(AuditLog::getAction)
                .contains("ACCOUNT_AGGREGATOR_TRANSACTION_MISSING");
    }

    @Test
    void aTransactionWithNoExternalTxnIdIsNeverFlaggedAsMissing() {
        // The identity ceiling the scope doc names: some FIPs don't populate txnId at all. Proven
        // for real here, not just in AccountAggregatorTransactionDiffServiceTest's mocked unit
        // test -- the fallback (fingerprint-only) dedup path still has to survive a real round trip
        // through the repository's own exists-by-fingerprint query.
        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), eq(FROM), eq(TO)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of(
                        new SetuFiDataTransaction(null, "DEBIT", new BigDecimal("125.00"),
                                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                                "No stable id from this FIP", new BigDecimal("1000.00"), null))));
        fetchService.sync(link, FROM, TO);
        UUID persistedId = ownTransactions().get(0).getId();

        reset(gateway);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), eq(FROM), eq(TO)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of()));
        fetchService.sync(link, FROM, TO);

        Transaction reloaded = transactionRepository.findById(persistedId).orElseThrow();
        assertThat(reloaded.isPendingBankCorrection()).isFalse();
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(persistedId)).isEmpty();
    }
}
