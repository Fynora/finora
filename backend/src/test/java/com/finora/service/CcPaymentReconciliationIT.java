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
    void aBillPaidAWeekAfterTheStatementDate_isExcludedFromSpending() {
        // 13 days before the due date: outside the old due date +/- 10, inside the window that opens
        // the day after the statement date. The statement date is read back from the database.
        Fixture f = fixture("2500.00");
        f.statement().setStatementPeriodEnd(DUE.minusDays(20));
        statementImportRepository.save(f.statement());
        Transaction payment = save(txn(f.userId(), f.savings(), DUE.minusDays(13), "2500.00",
                "UPI/DR/100000000001/CRED Clu/UTIB/cred.club@axisb/"));

        reconciliationService.reconcileForUser(f.userId());

        assertThat(transactionGraphService.ccPaymentFromTransactionIds(List.of(payment))).containsExactly(payment.getId());
    }

    @Test
    void aLinkAnEarlierRuleWrote_isRejected_onceThePaymentFallsOutsideTheWindow() {
        // A payment made on the statement date pays the bill before it. A link to it, written as an
        // earlier version of this pass wrote it, must be released back into spending on the next run.
        Fixture f = fixture("2500.00");
        f.statement().setStatementPeriodEnd(DUE.minusDays(20));
        statementImportRepository.save(f.statement());
        Transaction payment = save(txn(f.userId(), f.savings(), DUE.minusDays(20), "2500.00", "NEFT CC PAYMENT"));
        transactionGraphService.linkAll(List.of(new TransactionGraphService.PendingEdge(f.userId(), payment.getId(),
                f.charge().getId(), TransactionRelationship.RelationshipType.CC_PAYMENT, payment.getAmount(), 70,
                SourceTrust.of(payment.getSource()), TransactionRelationship.Status.CANDIDATE,
                TransactionRelationship.DetectionMethod.RULE_ENGINE,
                java.util.Map.<String, Object>of("type", "CC_PAYMENT_MATCH", "statementImportId", f.statement().getId().toString()))));
        assertThat(transactionGraphService.ccPaymentFromTransactionIds(List.of(payment))).containsExactly(payment.getId());

        reconciliationService.reconcileForUser(f.userId());

        assertThat(transactionGraphService.ccPaymentFromTransactionIds(List.of(payment))).isEmpty();
    }

    @Test
    void aBillPaidInParts_staysLinkedPartByPart_acrossRuns() {
        // Two payments to the bill-payment app that add up to the bill. The second run re-reads the
        // links the first wrote, and must keep both rather than reject the second as a duplicate.
        Fixture f = fixture("2500.00");
        f.statement().setStatementPeriodEnd(DUE.minusDays(20));
        statementImportRepository.save(f.statement());
        Transaction first = save(txn(f.userId(), f.savings(), DUE.minusDays(15), "1000.00",
                "UPI/DR/100000000001/CRED Clu/UTIB/cred.club@axisb/"));
        Transaction second = save(txn(f.userId(), f.savings(), DUE.minusDays(5), "1500.00",
                "UPI/DR/100000000002/CRED Clu/UTIB/cred.club@axisb/"));

        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactlyInAnyOrder(first.getId(), second.getId());

        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(transactionGraphService.ccPaymentFromTransactionIds(List.of(first, second)))
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void aBillPaidEntirelyInSmallParts_staysLinked_acrossRuns() {
        // 21 payments, each under the 5% floor a lone payment needs, adding up to exactly the bill.
        // None could pay it alone, so the second run must keep them as the exact total they are,
        // not reject them all -- a rejected pair is never linked again.
        Fixture f = fixture("2500.00");
        f.statement().setStatementPeriodEnd(DUE.minusDays(20));
        statementImportRepository.save(f.statement());
        java.util.List<UUID> parts = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            parts.add(save(txn(f.userId(), f.savings(), DUE.minusDays(1 + i % 15), i < 20 ? "120.00" : "100.00",
                    "UPI/DR/1000000000" + String.format("%02d", i) + "/CRED Clu/UTIB/cred.club@axisb/")).getId());
        }

        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactlyInAnyOrderElementsOf(parts);

        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactlyInAnyOrderElementsOf(parts);
    }

    /** Live CC_PAYMENT edges from this payment, and whether any of its edges is REJECTED. */
    private boolean hasRejectedEdge(Fixture f, Transaction payment) {
        return relationshipRepository.findByUserIdAndRelationshipTypeAndSupersededByIsNull(
                        f.userId(), TransactionRelationship.RelationshipType.CC_PAYMENT).stream()
                .anyMatch(e -> e.getFromTransactionId().equals(payment.getId())
                        && e.getStatus() == TransactionRelationship.Status.REJECTED);
    }

    @Test
    void aPartWhoseFirstPaymentIsGone_isReleased_andLinksAgainOnceTheBillHasAFirstPaymentAgain() {
        Fixture f = fixture("2500.00");
        f.statement().setStatementPeriodEnd(DUE.minusDays(20));
        statementImportRepository.save(f.statement());
        Transaction first = save(txn(f.userId(), f.savings(), DUE.minusDays(5), "1500.00",
                "UPI/DR/100000000001/CRED Clu/UTIB/cred.club@axisb/"));
        Transaction smallPart = save(txn(f.userId(), f.savings(), DUE.minusDays(15), "60.00",
                "UPI/DR/100000000002/CRED Clu/UTIB/cred.club@axisb/"));
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactlyInAnyOrder(first.getId(), smallPart.getId());

        // The first payment is deleted: the small part cannot pay the bill alone, so it goes back to
        // spending -- released, not rejected.
        transactionRepository.delete(transactionRepository.findById(first.getId()).orElseThrow());
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).isEmpty();
        assertThat(hasRejectedEdge(f, smallPart)).isFalse();
        assertThat(transactionGraphService.ccPaymentFromTransactionIds(List.of(smallPart))).isEmpty();

        // A new first payment arrives: the small part links again beside it.
        Transaction newFirst = save(txn(f.userId(), f.savings(), DUE.minusDays(3), "1400.00",
                "UPI/DR/100000000003/CRED Clu/UTIB/cred.club@axisb/"));
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactlyInAnyOrder(newFirst.getId(), smallPart.getId());
    }

    @Test
    void aPartNamingNoCard_isReleasedWhenASecondCardAppears_andLinksAgainWhenItGoes() {
        Fixture f = fixture("2500.00");
        f.statement().setStatementPeriodEnd(DUE.minusDays(20));
        statementImportRepository.save(f.statement());
        Transaction part = save(txn(f.userId(), f.savings(), DUE.minusDays(5), "1000.00",
                "UPI/DR/100000000001/CRED Clu/UTIB/cred.club@axisb/"));
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactly(part.getId());

        // With a second card, a part payment naming neither could be paying either: no longer linked,
        // and not blocked either.
        Account second = account(f.userId(), Account.Type.CREDIT_CARD, "XXXXXXXXXXXX9876");
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).isEmpty();
        assertThat(hasRejectedEdge(f, part)).isFalse();

        accountRepository.delete(accountRepository.findById(second.getId()).orElseThrow());
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactly(part.getId());
    }

    @Test
    void aPaymentLinkedToOneCardsBill_movesToTheCardWhoseStatementShowsItReceived() {
        // Linked by amount and window to the only bill imported. Then another card's statement is
        // imported, and it prints this very payment as received: that is proof of which card it paid,
        // so the payment becomes a transfer to that card and the guessed link is withdrawn.
        Fixture f = fixture("2500.00");
        f.statement().setStatementPeriodEnd(DUE.minusDays(20));
        statementImportRepository.save(f.statement());
        Transaction payment = save(txn(f.userId(), f.savings(), DUE.minusDays(10), "1500.00",
                "UPI/DR/100000000001/CRED Clu/UTIB/cred.club@axisb/"));
        reconciliationService.reconcileForUser(f.userId());
        assertThat(livePaymentsFor(f)).containsExactly(payment.getId());

        Account otherCard = account(f.userId(), Account.Type.CREDIT_CARD, "XXXXXXXXXXXX9876");
        Transaction received = txn(f.userId(), otherCard, DUE.minusDays(10), "1500.00", "BBPS PAYMENT RECEIVED - DP100000000001");
        received.setTxnType(Transaction.Type.INCOME);
        received = save(received);
        reconciliationService.reconcileForUser(f.userId());

        Transaction after = transactionRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.isTransfer()).isTrue();
        assertThat(after.getTransferPairId()).isEqualTo(received.getId());
        assertThat(livePaymentsFor(f)).as("the guessed link to the other card's bill is withdrawn").isEmpty();
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
