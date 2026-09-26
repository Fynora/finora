package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.service.FlowClassifier.FlowClass;
import com.finora.service.FlowClassifier.FlowDecision;
import com.finora.service.FlowClassifier.FlowReason;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a transaction did to the user's wealth, independent of its direction. Every narration below
 * is synthetic -- shaped like a real rail's narration, never copied from a real statement.
 */
class FlowClassifierTest {

    private static Transaction credit(String description) {
        Transaction t = new Transaction();
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(new BigDecimal("100.00"));
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setSource(Transaction.Source.CSV_IMPORT); // the entity defaults to MANUAL, which is the user's own word
        return t;
    }

    private static Transaction debit(String description) {
        Transaction t = credit(description);
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    private static FlowDecision savings(Transaction t) { return FlowClassifier.classify(t, Account.Type.SAVINGS); }
    private static FlowDecision card(Transaction t) { return FlowClassifier.classify(t, Account.Type.CREDIT_CARD); }

    // ---- status-driven: reconciliation already decided, the classifier only names it ----

    @Test void pairedTransferCredit_isTransfer() {
        Transaction t = credit("NEFT FROM OWN ACCOUNT");
        t.setTransfer(true);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER));
    }

    @Test void matchedRefundLeg_isLinkedRefund() {
        Transaction t = credit("MERCHANTCO ORDER 1001");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.REFUND, FlowReason.LINKED_REFUND));
    }

    @Test void matchedReversalLeg_isAdjustment() {
        Transaction t = credit("MERCHANTCO 1001");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.REVERSAL));
    }

    // ---- keyword-driven, any account ----

    @Test void unmatchedRefundKeyword_isUnlinkedRefund() {
        assertThat(savings(credit("REFUND MERCHANTCO ORDER 1001")))
                .isEqualTo(new FlowDecision(FlowClass.REFUND, FlowReason.UNLINKED_REFUND));
    }

    @Test void unmatchedReversalKeyword_isAdjustment() {
        assertThat(savings(credit("UPI PAYMENT REVERSED 111111111111")))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.REVERSAL));
    }

    @Test void governmentRefund_isTaxRefundIncome() {
        Transaction t = credit("TAX REFUND CPC AY 2026");
        t.setCounterpartyType(CounterpartyType.GOVERNMENT);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.TAX_REFUND));
    }

    // ---- savings-side inflows ----

    @Test void investmentRedemption_isInvestmentWithdrawal() {
        assertThat(savings(credit("NEFT MF REDEMPTION FUNDHOUSE")))
                .isEqualTo(new FlowDecision(FlowClass.INVESTMENT, FlowReason.INVESTMENT_WITHDRAWAL));
    }

    @Test void fdClosure_isInvestmentWithdrawal() {
        assertThat(savings(credit("FD CLOSURE PROCEEDS 000123")))
                .isEqualTo(new FlowDecision(FlowClass.INVESTMENT, FlowReason.INVESTMENT_WITHDRAWAL));
    }

    @Test void loanDisbursal_isLiabilityDrawdown() {
        assertThat(savings(credit("LOAN DISBURSAL LENDERCO LAN 99887766")))
                .isEqualTo(new FlowDecision(FlowClass.LIABILITY, FlowReason.LOAN_DRAWDOWN));
    }

    @Test void salary_isIncome() {
        assertThat(savings(credit("NEFT ACME TECHNOLOGIES SALARY JUL")))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.SALARY));
    }

    @Test void salaryKeyword_beatsPersonCounterparty() {
        Transaction t = credit("IMPS SALARY FOR JULY A PROPRIETOR");
        t.setCounterpartyType(CounterpartyType.PERSON);
        assertThat(savings(t).flowClass()).isEqualTo(FlowClass.INCOME);
    }

    @Test void savingsInterest_isIncome() {
        assertThat(savings(credit("INT.PD:01-07-2026 TO 30-09-2026")))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.INTEREST));
    }

    @Test void personInflow_isUnresolved() {
        Transaction t = credit("UPI/111111111111/A PERSON/person@okbank");
        t.setCounterpartyType(CounterpartyType.PERSON);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.UNRESOLVED, FlowReason.PERSON_INFLOW));
    }

    @Test void businessInflowWithNoSignal_isOtherIncome() {
        Transaction t = credit("NEFT CLIENTCO PVT LTD INV 42");
        t.setCounterpartyType(CounterpartyType.BUSINESS);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.OTHER_INCOME));
    }

    @Test void nullDescription_incomeFallsToOtherIncome() {
        assertThat(savings(credit(null))).isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.OTHER_INCOME));
    }

    @Test void nullAccountType_isTreatedAsNonCard() {
        assertThat(FlowClassifier.classify(credit("NEFT CLIENTCO PVT LTD"), null).flowClass()).isEqualTo(FlowClass.INCOME);
    }

    // ---- credit-card account: a credit is never earned income by default ----

    @Test void cardPaymentReceived_isTransfer() {
        assertThat(card(credit("PAYMENT RECEIVED THANK YOU")))
                .isEqualTo(new FlowDecision(FlowClass.TRANSFER, FlowReason.CARD_PAYMENT_RECEIVED));
    }

    @Test void cardBbpsPayment_isTransfer() {
        assertThat(card(credit("BBPS PAYMENT 123456")).flowClass()).isEqualTo(FlowClass.TRANSFER);
    }

    @Test void cardFeeWaiver_isAdjustment() {
        assertThat(card(credit("FUEL SURCHARGE WAIVER")))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.CARD_ADJUSTMENT));
    }

    @Test void cardEmiConversionCredit_isAdjustment() {
        assertThat(card(credit("EMI CONVERSION MERCHANTCO 1001")))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.CARD_ADJUSTMENT));
    }

    @Test void cardCashback_isRewardIncome() {
        assertThat(card(credit("CASHBACK CREDITED")))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.REWARD));
    }

    @Test void cardMerchantCreditWithNoSignal_isUnresolved() {
        // Shape of a merchant credit on a card statement with no refund word: honest unknown, never income.
        assertThat(card(credit("UPI MERCHANTCO 111111111111")))
                .isEqualTo(new FlowDecision(FlowClass.UNRESOLVED, FlowReason.CARD_UNEXPLAINED_CREDIT));
    }

    @Test void cardSalaryLookalike_isStillNotIncome() {
        assertThat(card(credit("NEFT SALARY ADVANCE")).flowClass()).isNotEqualTo(FlowClass.INCOME);
    }

    // ---- outflows: named only, totals unchanged in Plan 1 ----

    @Test void ordinaryDebit_isExpense() {
        assertThat(savings(debit("UPI MERCHANTCO"))).isEqualTo(new FlowDecision(FlowClass.EXPENSE, FlowReason.PURCHASE));
    }

    @Test void pairedTransferDebit_isTransfer() {
        Transaction t = debit("NEFT TO OWN ACCOUNT");
        t.setTransfer(true);
        assertThat(savings(t).flowClass()).isEqualTo(FlowClass.TRANSFER);
    }

    @Test void investmentTransferDebit_isInvestmentContribution() {
        Transaction t = debit("NACH SIP FUNDHOUSE");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.INVESTMENT, FlowReason.INVESTMENT_CONTRIBUTION));
    }
}
