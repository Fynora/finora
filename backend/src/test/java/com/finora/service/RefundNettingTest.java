package com.finora.service;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-005. What a refunded purchase costs.
 *
 * <p>The bug was not that refunds were mishandled in some subtle corner -- it was that the two
 * screens reporting them each dropped one leg of a two-leg event and kept the other. A fully
 * refunded &#8377;500 order reported as a &#8377;500 loss in a month where nothing had been spent,
 * and {@code Account.balance} (which applied both legs, and was right) disagreed with the dashboard
 * by exactly that.
 *
 * <p>The cases below are the ones that distinguish the fix from the two wrong answers either side
 * of it: counting the purchase in full (the bug) and dropping it entirely (which gets partial
 * refunds wrong in the other direction).
 */
class RefundNettingTest {

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    /** BaseEntity's id is @GeneratedValue with no setter, so tests assign it the way every other
     *  unit test in this package does. Needed here because the netting is keyed on it. */
    private static Transaction withId(Transaction t, UUID id) {
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    private static Transaction expense(UUID id, String amount) {
        Transaction t = new Transaction();
        withId(t, id);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setAmount(money(amount));
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    /** The income side of a matched refund, exactly as ReconciliationService leaves it. */
    private static Transaction refundOf(UUID expenseId, String amount) {
        Transaction t = new Transaction();
        withId(t, UUID.randomUUID());
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(money(amount));
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        t.setRefundOfTransactionId(expenseId);
        return t;
    }

    /** The income side of a matched reversal, exactly as ReconciliationService leaves it -- same
     *  shape as {@link #refundOf}, different status. */
    private static Transaction reversalOf(UUID expenseId, String amount) {
        Transaction t = new Transaction();
        withId(t, UUID.randomUUID());
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(money(amount));
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        t.setRefundOfTransactionId(expenseId);
        return t;
    }

    private static Transaction income(String amount) {
        Transaction t = new Transaction();
        withId(t, UUID.randomUUID());
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(money(amount));
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    @Test
    @DisplayName("BH-005: a fully refunded purchase costs nothing, and is not reported as a loss")
    void aFullRefundNetsThePurchaseToZero() {
        UUID purchaseId = UUID.randomUUID();
        Transaction purchase = expense(purchaseId, "500.00");
        List<Transaction> ledger = List.of(purchase, refundOf(purchaseId, "500.00"));

        RefundNetting refunds = RefundNetting.from(ledger);
        List<Transaction> reportable = RefundNetting.reportable(ledger);

        assertThat(reportable)
                .as("the refund's income leg is excluded; the purchase is NOT -- it is netted instead")
                .containsExactly(purchase);
        assertThat(refunds.reportableAmount(purchase))
                .as("the money came back, so the purchase cost nothing")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("BH-005: a partial refund leaves the difference, which is why dropping the expense is also wrong")
    void aPartialRefundLeavesTheDifference() {
        // This is the case that rules out the obvious alternative fix. Excluding the expense
        // alongside the income would report 0 spend here; counting it in full reports 500. Both
        // are wrong: the order cost 200.
        UUID purchaseId = UUID.randomUUID();
        Transaction purchase = expense(purchaseId, "500.00");

        RefundNetting refunds = RefundNetting.from(List.of(purchase, refundOf(purchaseId, "300.00")));

        assertThat(refunds.reportableAmount(purchase)).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("two partial refunds against one purchase are summed")
    void refundsAgainstOnePurchaseAccumulate() {
        // Reconciliation can match more than one income row to the same expense -- two partial
        // refunds for one order is ordinary. Taking only the first would leave the second counted
        // as spend.
        UUID purchaseId = UUID.randomUUID();
        Transaction purchase = expense(purchaseId, "500.00");

        RefundNetting refunds = RefundNetting.from(
                List.of(purchase, refundOf(purchaseId, "300.00"), refundOf(purchaseId, "150.00")));

        assertThat(refunds.reportableAmount(purchase)).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("an over-refund floors at zero rather than becoming negative spend")
    void anOverRefundCannotProduceNegativeSpend() {
        // ReconciliationService already refuses to match a refund larger than its purchase, so this
        // should be unreachable -- but a negative expense would flow into a category total and a
        // savings rate as a silently wrong number, and that is not worth leaving to one guarantee.
        UUID purchaseId = UUID.randomUUID();
        Transaction purchase = expense(purchaseId, "500.00");

        RefundNetting refunds = RefundNetting.from(List.of(purchase, refundOf(purchaseId, "900.00")));

        assertThat(refunds.reportableAmount(purchase)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("ordinary rows are untouched")
    void unrelatedRowsAreReportedAsTheyStand() {
        Transaction salary = income("50000.00");
        Transaction groceries = expense(UUID.randomUUID(), "2400.00");
        UUID refundedId = UUID.randomUUID();
        Transaction refunded = expense(refundedId, "500.00");

        List<Transaction> ledger = List.of(salary, groceries, refunded, refundOf(refundedId, "500.00"));
        RefundNetting refunds = RefundNetting.from(ledger);

        assertThat(refunds.reportableAmount(salary)).isEqualByComparingTo("50000.00");
        assertThat(refunds.reportableAmount(groceries))
                .as("a refund against one purchase must not reduce another")
                .isEqualByComparingTo("2400.00");
        assertThat(RefundNetting.reportable(ledger)).containsExactly(salary, groceries, refunded);
    }

    @Test
    @DisplayName("a REVERSAL leg nets the same as a REFUND leg -- the split only changes the label")
    void aReversalNetsThePurchaseTheSameWayARefundDoes() {
        UUID purchaseId = UUID.randomUUID();
        Transaction purchase = expense(purchaseId, "1200.00");
        List<Transaction> ledger = List.of(purchase, reversalOf(purchaseId, "1200.00"));

        RefundNetting refunds = RefundNetting.from(ledger);

        assertThat(RefundNetting.reportable(ledger))
                .as("the reversal's income leg is excluded, same as a refund's")
                .containsExactly(purchase);
        assertThat(refunds.reportableAmount(purchase)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("duplicates and transfers stay excluded -- this replaces that filter, it does not weaken it")
    void duplicatesAndTransfersAreStillExcluded() {
        Transaction duplicate = expense(UUID.randomUUID(), "100.00");
        duplicate.setIsDuplicateOf(UUID.randomUUID());
        Transaction transfer = expense(UUID.randomUUID(), "100.00");
        transfer.setTransfer(true);
        Transaction real = expense(UUID.randomUUID(), "100.00");

        assertThat(RefundNetting.reportable(List.of(duplicate, transfer, real)))
                .containsExactly(real);
    }

    @Test
    @DisplayName("Phase 4: a SUPERSEDED row (its own statement was replaced) is excluded, like a transfer")
    void supersededRowsAreExcluded() {
        // proposal §0.6: "only the active (non-superseded) statement... participates in
        // Account.balance, coverage, and Insights" -- the row itself is never deleted (see
        // StatementImportService.supersede), only its reconciliationStatus changes, same
        // precedent as INVESTMENT_TRANSFER.
        Transaction superseded = expense(UUID.randomUUID(), "100.00");
        superseded.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
        Transaction real = expense(UUID.randomUUID(), "100.00");

        assertThat(RefundNetting.reportable(List.of(superseded, real))).containsExactly(real);
    }

    @Test
    @DisplayName("a CC_PAYMENT-settling payment is excluded like a transfer -- the charges it settles stay counted")
    void ccPaymentSettlementsAreExcludedButTheirChargesStay() {
        // Reconciliation-evolution-roadmap-proposal.md Part 4/10 "Net worth & cash flow,
        // graph-aware": a savings-side payment that settles a card statement is money moving
        // between the user's own accounts, same as a TRANSFER -- only the payment nets out, the
        // charges it settles are real spend and must stay.
        Transaction amazonCharge = expense(UUID.randomUUID(), "5000.00");
        Transaction cardPayment = expense(UUID.randomUUID(), "5000.00");

        List<Transaction> reportable = RefundNetting.reportable(
                List.of(amazonCharge, cardPayment), Set.of(cardPayment.getId()));

        assertThat(reportable).containsExactly(amazonCharge);
    }

    @Test
    @DisplayName("the one-argument reportable() overload behaves as if no CC_PAYMENT edges exist")
    void theLegacyOverloadIsUnaffectedByCcPayments() {
        Transaction real = expense(UUID.randomUUID(), "100.00");

        assertThat(RefundNetting.reportable(List.of(real))).containsExactly(real);
    }

    @Test
    @DisplayName("a ledger with no refunds allocates nothing and reports every amount unchanged")
    void aLedgerWithNoRefundsIsTheIdentity() {
        Transaction groceries = expense(UUID.randomUUID(), "2400.00");

        assertThat(RefundNetting.from(List.of(groceries, income("100.00"))).reportableAmount(groceries))
                .isEqualByComparingTo("2400.00");
        assertThat(RefundNetting.NONE.reportableAmount(groceries)).isEqualByComparingTo("2400.00");
    }

    // ---- unlinked refunds and card adjustments give spend back ----

    private static final FlowTotals.Context NO_CARDS = FlowTotals.context(List.of(), List.of());

    private static Transaction credit(String amount, String description) {
        Transaction t = new Transaction();
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(money(amount));
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setSource(Transaction.Source.CSV_IMPORT);
        return t;
    }

    @Test
    @DisplayName("an unlinked refund counts as negative spend; a purchase keeps its reportable amount")
    void unlinkedRefund_isNegativeSpend() {
        Transaction purchase = expense(UUID.randomUUID(), "1000.00");
        Transaction refund = credit("300.00", "REFUND FROM MERCHANTCO ORDER 1");
        RefundNetting spend = RefundNetting.from(List.of()).withUnlinkedOffsets(List.of(purchase, refund), NO_CARDS);

        assertThat(spend.countsAsSpend(purchase)).isTrue();
        assertThat(spend.countsAsSpend(refund)).isTrue();
        assertThat(spend.spendAmount(purchase)).isEqualByComparingTo("1000.00");
        assertThat(spend.spendAmount(refund)).isEqualByComparingTo("-300.00");
    }

    @Test
    @DisplayName("a refund reconciliation already LINKED is never also an offset -- it would be netted twice")
    void linkedRefund_isNotAlsoAnOffset() {
        UUID purchaseId = UUID.randomUUID();
        Transaction purchase = expense(purchaseId, "1000.00");
        Transaction linked = credit("300.00", "REFUND FROM MERCHANTCO ORDER 1");
        linked.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        linked.setRefundOfTransactionId(purchaseId);
        RefundNetting spend = RefundNetting.from(List.of(linked)).withUnlinkedOffsets(List.of(purchase, linked), NO_CARDS);

        assertThat(spend.countsAsSpend(linked)).isFalse();
        assertThat(spend.spendAmount(purchase)).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("income, a transfer from a person and a salary never offset spend")
    void otherCredits_areNotOffsets() {
        Transaction salary = credit("50000.00", "NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction person = credit("2000.00", "UPI-SUNIL VERMA-sampleuser@ybl-REF1");
        person.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        RefundNetting spend = RefundNetting.from(List.of()).withUnlinkedOffsets(List.of(salary, person), NO_CARDS);

        assertThat(spend.countsAsSpend(salary)).isFalse();
        assertThat(spend.countsAsSpend(person)).isFalse();
        // Safe on an income list: a non-offset credit is priced as it always was.
        assertThat(spend.spendAmount(salary)).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("an offset is found by identity when the row has no id yet")
    void offset_withoutId_isFoundByIdentity() {
        Transaction refund = credit("300.00", "REFUND FROM MERCHANTCO ORDER 1");
        RefundNetting spend = RefundNetting.from(List.of()).withUnlinkedOffsets(List.of(refund), NO_CARDS);
        assertThat(spend.countsAsSpend(refund)).isTrue();
        assertThat(spend.countsAsSpend(credit("300.00", "REFUND FROM MERCHANTCO ORDER 1"))).isFalse();
    }

    @Test
    @DisplayName("with no offsets among the rows, the same netting comes back")
    void noOffsets_returnsSameInstance() {
        RefundNetting base = RefundNetting.from(List.of());
        assertThat(base.withUnlinkedOffsets(List.of(expense(UUID.randomUUID(), "10.00")), NO_CARDS)).isSameAs(base);
    }

    @Test
    @DisplayName("totals floor at zero; a category pushed negative is dropped, a zero one kept")
    void floorsAndDrops() {
        assertThat(RefundNetting.floorAtZero(money("-5.00"))).isEqualByComparingTo("0");
        assertThat(RefundNetting.floorAtZero(money("5.00"))).isEqualByComparingTo("5.00");
        assertThat(RefundNetting.withoutNegativeSpend(java.util.Map.of(
                "Shopping", money("-200.00"), "Dining", money("0"), "Fuel", money("50.00"))))
                .containsOnlyKeys("Dining", "Fuel");
    }
}
