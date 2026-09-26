package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRelationshipRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CC_PAYMENT pass across several reconciliation runs, against real Postgres. Its failures
 * live between runs -- which edges an earlier run left, and what linkAll does with a pair that
 * already has an edge in any status -- so a single mocked run cannot show them.
 */
class CcPaymentReconciliationIT extends AbstractIntegrationTest {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private TransactionGraphService transactionGraphService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionRelationshipRepository relationshipRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;

    private static final LocalDate DUE = LocalDate.of(2026, 7, 15);

    private record Fixture(UUID userId, Account savings, Account card, StatementImport statement, Transaction charge) {}

    private Fixture fixture(String totalDue) {
        User user = new User();
        user.setEmail("cc-payment-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("CC Payment IT User");
        user.setPhoneVerified(true);
        UUID userId = userRepository.save(user).getId();

        Account savings = account(userId, Account.Type.SAVINGS, null);
        Account card = account(userId, Account.Type.CREDIT_CARD, "XXXXXXXXXXXX4321");

        StatementImport s = new StatementImport();
        s.setUserId(userId);
        s.setAccountId(card.getId());
        s.setFileName("card.pdf");
        s.setSourceFormat("PDF");
        s.setFileContent(new byte[]{1});
        s.setContentHash("cc-payment-it-" + UUID.randomUUID());
        s.setTotalAmountDue(new BigDecimal(totalDue));
        s.setPaymentDueDate(DUE);
        StatementImport statement = statementImportRepository.save(s);

        Transaction charge = txn(userId, card, DUE.minusDays(25), totalDue, "SHOP");
        charge.setStatementImportId(statement.getId());
        charge = transactionRepository.save(charge);
        return new Fixture(userId, savings, card, statement, charge);
    }

    private Account account(UUID userId, Account.Type type, String masked) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName(type + " account");
        a.setAccountType(type);
        a.setBalance(BigDecimal.ZERO);
        a.setAccountNumberMasked(masked);
        return accountRepository.save(a);
    }

    private Transaction txn(UUID userId, Account account, LocalDate date, String amount, String description) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(account.getId());
        t.setTxnDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        t.setSource(Transaction.Source.CSV_IMPORT);
        return t;
    }

    private Transaction save(Transaction t) {
        return transactionRepository.save(t);
    }

    /** Payments whose live CC_PAYMENT edges point at this statement's charge. */
    private Set<UUID> livePaymentsFor(Fixture f) {
        return relationshipRepository.findByUserIdAndRelationshipTypeAndSupersededByIsNull(
                        f.userId(), TransactionRelationship.RelationshipType.CC_PAYMENT)
                .stream()
                .filter(e -> e.getStatus() != TransactionRelationship.Status.REJECTED)
                .filter(e -> e.getToTransactionId().equals(f.charge().getId()))
                .map(TransactionRelationship::getFromTransactionId)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void aStatementLinkedByAnEarlierRun_isNotLinkedAgain_whenABetterPaymentArrivesLater() {
        Fixture f = fixture("2500.00");
        Transaction first = save(txn(f.userId(), f.savings(), DUE.minusDays(6), "2500.00", "NEFT CC PAYMENT"));
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactly(first.getId());

        // Closer to the due date: a fresh run would pick it. It must not be linked as well.
        save(txn(f.userId(), f.savings(), DUE, "2500.00", "NEFT CC PAYMENT"));
        reconciliationService.reconcileForUser(f.userId());

        assertThat(livePaymentsFor(f)).containsExactly(first.getId());
    }

    @Test
    void aPairWhoseEdgeWasRejected_doesNotBlockTheStatementFromItsNextBestPayment() {
        Fixture f = fixture("2500.00");
        Transaction best = save(txn(f.userId(), f.savings(), DUE, "2500.00", "NEFT CC PAYMENT"));
        Transaction nextBest = save(txn(f.userId(), f.savings(), DUE.minusDays(4), "2500.00", "NEFT CC PAYMENT"));
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactly(best.getId());

        // The best pair's edge is rejected (by a person, or by a later run of this pass). linkAll
        // never rewrites a pair that already has an edge in any status, so if the pass kept
        // picking `best`, it would claim it without writing anything, and the statement would
        // stay unsettled forever.
        relationshipRepository.findByUserIdAndRelationshipTypeAndSupersededByIsNull(
                        f.userId(), TransactionRelationship.RelationshipType.CC_PAYMENT)
                .forEach(e -> transactionGraphService.setStatus(f.userId(), e.getId(), TransactionRelationship.Status.REJECTED));
        reconciliationService.reconcileForUser(f.userId());

        assertThat(livePaymentsFor(f)).containsExactly(nextBest.getId());
    }

    @Test
    void deletingTheOnlyCardStatement_releasesItsPayment_fromBeingExcludedFromSpending() {
        Fixture f = fixture("2500.00");
        Transaction payment = save(txn(f.userId(), f.savings(), DUE, "2500.00", "NEFT CC PAYMENT"));
        reconciliationService.reconcileForUser(f.userId());
        assertThat(transactionGraphService.ccPaymentFromTransactionIds(List.of(payment))).containsExactly(payment.getId());

        // What StatementImportService.delete does to the rows: the charges go, the statement goes,
        // then a reconcile. Nothing on that path rejects graph edges itself.
        transactionRepository.delete(transactionRepository.findById(f.charge().getId()).orElseThrow());
        statementImportRepository.delete(f.statement());
        reconciliationService.reconcileForUser(f.userId());

        assertThat(transactionGraphService.ccPaymentFromTransactionIds(List.of(payment)))
                .as("the statement it settled is gone, so the payment is ordinary spend again")
                .isEmpty();
    }
}
