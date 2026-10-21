package com.finora.people;

import com.finora.entity.LoanEntry;
import com.finora.entity.ShareLink;
import com.finora.entity.SplitRule;
import com.finora.entity.Transaction;
import com.finora.service.FlowClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PeopleLedgerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID RENT = UUID.randomUUID();
    private static final String FLATMATE = "vpa:flatmate.a";
    private static final String OWNER = "vpa:owner.b";
    private static final String FRIEND = "vpa:friend.k";

    private final List<Transaction> rows = new ArrayList<>();

    private Transaction row(Transaction.Type type, int amount, LocalDate date, String key, UUID categoryId) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(USER);
        t.setTxnType(type);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(date);
        t.setCounterpartyKey(key);
        t.setCategoryId(categoryId);
        rows.add(t);
        return t;
    }

    private Transaction bill(int amount, LocalDate date) { return row(Transaction.Type.EXPENSE, amount, date, OWNER, RENT); }
    private Transaction share(int amount, LocalDate date) { return row(Transaction.Type.INCOME, amount, date, FLATMATE, RENT); }
    private static LocalDate d(int month, int day) { return LocalDate.of(2026, month, day); }
    private static ShareLink link(Transaction credit, Transaction bill, String amount) {
        return new ShareLink(USER, credit.getId(), bill.getId(), new BigDecimal(amount));
    }
    private static LoanEntry entry(Transaction t, LoanEntry.Direction dir, int amount) {
        return new LoanEntry(USER, FRIEND, t == null ? null : t.getId(), dir, BigDecimal.valueOf(amount));
    }
    private static SplitRule rentRule() { return new SplitRule(USER, FLATMATE, RENT, OWNER, null); }

    private PeopleLedger build(Collection<ShareLink> links, Collection<SplitRule> rules, Collection<LoanEntry> entries,
                               Set<String> repaymentKeys) {
        return PeopleLedger.build(rows, links, rules, entries, repaymentKeys);
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Test
    void explicitShareLowersItsBill() {
        Transaction bill = bill(30000, d(9, 1));
        Transaction credit = share(7500, d(9, 3));
        PeopleLedger l = build(List.of(link(credit, bill, "7500")), List.of(), List.of(), Set.of());
        assertThat(l.sharedByBillId().get(bill.getId())).isEqualByComparingTo("7500");
        assertThat(l.droppedIds()).contains(credit.getId());
        assertThat(l.creditDecision(credit)).isEqualTo(new FlowClassifier.FlowDecision(
                FlowClassifier.FlowClass.ADJUSTMENT, FlowClassifier.FlowReason.SHARE_OF_BILL));
    }

    @Test
    void shareCappedAtBillRemainder() {
        Transaction bill = bill(1000, d(9, 1));
        Transaction a = share(700, d(9, 2));
        Transaction b = share(700, d(9, 3));
        PeopleLedger l = build(List.of(link(a, bill, "700"), link(b, bill, "700")), List.of(), List.of(), Set.of());
        assertThat(l.sharesOf(bill.getId())).extracting(PeopleLedger.Share::amount)
                .usingElementComparator(BigDecimal::compareTo).containsExactlyInAnyOrder(bd("700"), bd("300"));
        assertThat(l.sharedByBillId().get(bill.getId())).isEqualByComparingTo("1000");
    }

    @Test
    void shareExactlyEqualToBill() {
        Transaction bill = bill(1000, d(9, 1));
        Transaction credit = share(1000, d(9, 2));
        PeopleLedger l = build(List.of(link(credit, bill, "1000")), List.of(), List.of(), Set.of());
        assertThat(l.sharedByBillId().get(bill.getId())).isEqualByComparingTo("1000");
    }

    @Test
    void onePaiseOverIsCapped() {
        Transaction bill = bill(1000, d(9, 1));
        Transaction credit = row(Transaction.Type.INCOME, 0, d(9, 2), FLATMATE, RENT);
        credit.setAmount(bd("1000.01"));
        PeopleLedger l = build(List.of(link(credit, bill, "1000.01")), List.of(), List.of(), Set.of());
        assertThat(l.sharedByBillId().get(bill.getId())).isEqualByComparingTo("1000");
    }

    @Test
    void creditAcrossTwoBills() {
        Transaction first = bill(1000, d(9, 1));
        Transaction second = bill(800, d(9, 4));
        Transaction credit = share(1500, d(9, 5));
        PeopleLedger l = build(List.of(link(credit, first, "1000"), link(credit, second, "500")), List.of(), List.of(), Set.of());
        assertThat(l.sharedByBillId().get(first.getId())).isEqualByComparingTo("1000");
        assertThat(l.sharedByBillId().get(second.getId())).isEqualByComparingTo("500");
    }

    @Test
    void ruleLinksEveryMonthBackwards() {
        for (int m = 7; m <= 9; m++) { bill(30000, d(m, 1)); share(7500, d(m, 4)); }
        PeopleLedger l = build(List.of(), List.of(rentRule()), List.of(), Set.of());
        assertThat(l.sharedByBillId()).hasSize(3).allSatisfy((id, v) -> assertThat(v).isEqualByComparingTo("7500"));
        assertThat(rows.stream().filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .flatMap(t -> l.sharesOf(t.getId()).stream())).allMatch(PeopleLedger.Share::fromRule);
    }

    @Test
    void ruleSpillsAcrossTwoBillsOfSameIdentity() {
        Transaction first = bill(15000, d(9, 1));
        Transaction second = bill(15000, d(9, 3));
        share(20000, d(9, 4));
        PeopleLedger l = build(List.of(), List.of(rentRule()), List.of(), Set.of());
        // Nearest before the credit first: the 3rd, then the 1st.
        assertThat(l.sharedByBillId().get(second.getId())).isEqualByComparingTo("15000");
        assertThat(l.sharedByBillId().get(first.getId())).isEqualByComparingTo("5000");
    }

    @Test
    void rulePrefersBillBeforeCredit() {
        Transaction before = bill(30000, d(9, 1));
        Transaction after = bill(30000, d(9, 9));
        share(7500, d(9, 6));
        PeopleLedger l = build(List.of(), List.of(rentRule()), List.of(), Set.of());
        assertThat(l.sharedByBillId()).containsOnlyKeys(before.getId());
        assertThat(l.sharesOf(after.getId())).isEmpty();
    }

    @Test
    void ruleIgnoresBillOutside14Days() {
        bill(30000, d(9, 1));
        share(7500, d(9, 16));
        PeopleLedger l = build(List.of(), List.of(rentRule()), List.of(), Set.of());
        assertThat(l.sharedByBillId()).isEmpty();
        assertThat(l.droppedIds()).isEmpty();
    }

    @Test
    void explicitAnswerOutranksRule() {
        bill(30000, d(9, 1));
        Transaction credit = share(7500, d(9, 4));
        PeopleLedger l = build(List.of(), List.of(rentRule()),
                List.of(new LoanEntry(USER, FLATMATE, credit.getId(), LoanEntry.Direction.PAID_BACK, bd("7500"))), Set.of());
        assertThat(l.sharedByBillId()).isEmpty();
        assertThat(l.creditDecision(credit)).isEqualTo(new FlowClassifier.FlowDecision(
                FlowClassifier.FlowClass.ADJUSTMENT, FlowClassifier.FlowReason.PAID_BACK));
    }

    @Test
    void deadRowsDropOutOfLinks() {
        Transaction bill = bill(30000, d(9, 1));
        Transaction duplicate = share(7500, d(9, 3));
        duplicate.setIsDuplicateOf(UUID.randomUUID());
        Transaction superseded = share(7500, d(9, 4));
        superseded.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
        Transaction absent = share(7500, d(9, 5));
        rows.remove(absent);
        PeopleLedger l = build(List.of(link(duplicate, bill, "7500"), link(superseded, bill, "7500"), link(absent, bill, "7500")),
                List.of(), List.of(), Set.of());
        assertThat(l.sharedByBillId()).isEmpty();
        assertThat(l.droppedIds()).isEmpty();
    }

    @Test
    void lentDebitIsDroppedUntilLetGo() {
        Transaction lent = row(Transaction.Type.EXPENSE, 2000, d(9, 3), FRIEND, null);
        assertThat(build(List.of(), List.of(), List.of(entry(lent, LoanEntry.Direction.LENT, 2000)), Set.of()).droppedIds())
                .containsExactly(lent.getId());
        assertThat(build(List.of(), List.of(), List.of(entry(lent, LoanEntry.Direction.LENT, 2000),
                entry(lent, LoanEntry.Direction.LET_GO, 2000)), Set.of()).droppedIds()).isEmpty();
    }

    /** Lent 2000, 1500 came back, the rest let go: only 500 became spending, not the whole 2000. */
    @Test
    void letGoAfterPartRepaymentCountsOnlyTheRest() {
        Transaction lent = row(Transaction.Type.EXPENSE, 2000, d(9, 3), FRIEND, null);
        Transaction back = row(Transaction.Type.INCOME, 1500, d(9, 20), FRIEND, null);
        PeopleLedger l = build(List.of(), List.of(), List.of(entry(lent, LoanEntry.Direction.LENT, 2000),
                entry(back, LoanEntry.Direction.PAID_BACK, 1500), entry(lent, LoanEntry.Direction.LET_GO, 500)), Set.of());
        assertThat(l.droppedIds()).doesNotContain(lent.getId());
        assertThat(l.sharedByBillId().get(lent.getId())).isEqualByComparingTo("1500");
        assertThat(l.balances()).doesNotContainKey(FRIEND);
    }

    @Test
    void balanceGoesToZeroAndPast() {
        Transaction lent = row(Transaction.Type.EXPENSE, 2000, d(9, 3), FRIEND, null);
        Transaction back1 = row(Transaction.Type.INCOME, 1500, d(9, 20), FRIEND, null);
        Transaction back2 = row(Transaction.Type.INCOME, 800, d(9, 25), FRIEND, null);
        PeopleLedger l = build(List.of(), List.of(), List.of(entry(lent, LoanEntry.Direction.LENT, 2000),
                entry(back1, LoanEntry.Direction.PAID_BACK, 1500)), Set.of());
        assertThat(l.balances().get(FRIEND).owedToUser()).isEqualByComparingTo("500");
        assertThat(l.balances().get(FRIEND).since()).isEqualTo(d(9, 3));
        PeopleLedger past = build(List.of(), List.of(), List.of(entry(lent, LoanEntry.Direction.LENT, 2000),
                entry(back1, LoanEntry.Direction.PAID_BACK, 1500), entry(back2, LoanEntry.Direction.PAID_BACK, 800)), Set.of());
        assertThat(past.balances().get(FRIEND).owedToUser()).isEqualByComparingTo("-300");
        PeopleLedger zero = build(List.of(), List.of(), List.of(entry(lent, LoanEntry.Direction.LENT, 2000),
                entry(back1, LoanEntry.Direction.PAID_BACK, 1500), entry(null, LoanEntry.Direction.CASH_SETTLED, 500)), Set.of());
        assertThat(zero.balances()).doesNotContainKey(FRIEND);
    }

    /** "They owed you" (spec §6 D): money back for a debt Fynora never saw. It leaves income, and it
     *  must not read as the user now owing them -- only money back past an open loan flips. */
    @Test
    void paidBackWithNothingLentOpensNoBalance() {
        Transaction back = row(Transaction.Type.INCOME, 500, d(9, 20), FRIEND, null);
        PeopleLedger l = build(List.of(), List.of(), List.of(entry(back, LoanEntry.Direction.PAID_BACK, 500)), Set.of());
        assertThat(l.balances()).doesNotContainKey(FRIEND);
        assertThat(l.creditDecision(back)).isNotNull();
    }

    @Test
    void repaymentRuleStopsAtZero() {
        Transaction lent = row(Transaction.Type.EXPENSE, 1000, d(9, 1), FRIEND, null);
        row(Transaction.Type.INCOME, 600, d(9, 5), FRIEND, null);
        row(Transaction.Type.INCOME, 600, d(9, 9), FRIEND, null);
        PeopleLedger l = build(List.of(), List.of(), List.of(entry(lent, LoanEntry.Direction.LENT, 1000)), Set.of(FRIEND));
        // 600 then 400 of the second: the balance stops at zero, never flips through a rule.
        assertThat(l.balances()).doesNotContainKey(FRIEND);
    }

    /** A bill the user answered as a loan is not a shared bill: a rule must not hang shares on it,
     *  or the flatmates' money would leave every figure along with the lent payment. */
    @Test
    void ruleSkipsABillMarkedLent() {
        Transaction bill = bill(30000, d(9, 1));
        share(7500, d(9, 4));
        PeopleLedger l = build(List.of(), List.of(rentRule()),
                List.of(new LoanEntry(USER, OWNER, bill.getId(), LoanEntry.Direction.LENT, bd("30000"))), Set.of());
        assertThat(l.sharesOf(bill.getId())).isEmpty();
        assertThat(l.droppedIds()).containsExactly(bill.getId());
    }

    /** A row matched as a transfer, refund or reversal after a share was linked (or that a rule would
     *  reach) takes no share: it is not spending, so the credit would leave every figure for nothing. */
    @Test
    void pairedRowsTakeNoShare() {
        Transaction pairedBill = bill(30000, d(9, 1));
        Transaction credit = share(7500, d(9, 4));
        pairedBill.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        PeopleLedger linked = build(List.of(link(credit, pairedBill, "7500")), List.of(), List.of(), Set.of());
        assertThat(linked.sharesOf(pairedBill.getId())).isEmpty();
        assertThat(linked.droppedIds()).doesNotContain(credit.getId());

        rows.clear();
        Transaction realBill = bill(30000, d(9, 1));
        Transaction reversal = bill(30000, d(9, 2));
        reversal.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        Transaction refundCredit = share(7500, d(9, 4));
        refundCredit.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        Transaction plainCredit = share(7500, d(9, 5));
        PeopleLedger ruled = build(List.of(), List.of(rentRule()), List.of(), Set.of());
        assertThat(ruled.sharesOf(reversal.getId())).isEmpty();
        assertThat(ruled.droppedIds()).doesNotContain(refundCredit.getId()).contains(plainCredit.getId());
        assertThat(ruled.sharesOf(realBill.getId())).extracting(PeopleLedger.Share::creditId).containsExactly(plainCredit.getId());
    }

    @Test
    void weakKeyGetsNoRuleMatch() {
        row(Transaction.Type.EXPENSE, 30000, d(9, 1), OWNER, RENT);
        row(Transaction.Type.INCOME, 7500, d(9, 4), "cut:abc", RENT);
        PeopleLedger l = build(List.of(), List.of(new SplitRule(USER, "cut:abc", RENT, OWNER, null)), List.of(), Set.of());
        assertThat(l.sharedByBillId()).isEmpty();
    }
}
