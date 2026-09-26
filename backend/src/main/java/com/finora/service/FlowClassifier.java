package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyType;

import java.util.List;

/**
 * What a transaction did to the user's wealth -- its FLOW CLASS -- as distinct from its direction
 * ({@link Transaction.Type}), its purpose (category) and who was on the other side (counterparty).
 *
 * <h2>Why this exists</h2>
 * Every income figure used to be "credits minus the exceptions reconciliation had detected". Any
 * credit no detector recognised -- money from a person, an FD maturity, a loan disbursal, any credit
 * on a credit-card account -- fell back to income. This class makes the default for an unknown credit
 * {@link FlowClass#UNRESOLVED} rather than a confident claim of income.
 *
 * <h2>Derived, never stored</h2>
 * Every input is state other passes already maintain (reconciliation status, transfer flag,
 * counterparty type, narration, account type). Computing it at read time means it can never
 * disagree with them after a re-run. User overrides (a later plan) will be an input here, not a cache.
 *
 * <h2>Scope</h2>
 * Outflows are NAMED here but every spend total is unchanged; only income totals read this.
 * Bump {@link #VERSION} whenever a rule changes what an existing row classifies as.
 */
public final class FlowClassifier {

    // 2: a credit the user entered by hand, or put in their Salary category themselves (or through a
    //    rule they taught), is income even when the narration names a person.
    public static final short VERSION = 2;

    public enum FlowClass { INCOME, EXPENSE, REFUND, TRANSFER, INVESTMENT, LIABILITY, ADJUSTMENT, UNRESOLVED }

    public enum FlowReason {
        SALARY, INTEREST, REWARD, TAX_REFUND, OTHER_INCOME, USER_ENTERED,
        PURCHASE,
        LINKED_REFUND, UNLINKED_REFUND, REVERSAL, CARD_ADJUSTMENT,
        OWN_ACCOUNT_TRANSFER, CARD_PAYMENT_RECEIVED,
        INVESTMENT_CONTRIBUTION, INVESTMENT_WITHDRAWAL,
        LOAN_DRAWDOWN,
        PERSON_INFLOW, CARD_UNEXPLAINED_CREDIT
    }

    public record FlowDecision(FlowClass flowClass, FlowReason reason) {}

    // Word-start matches against CategoryRules.normalize output (lower-case, punctuation -> space).
    // Initial lists; the corpus probe (FlowClassCorpusProbe) is the evidence that confirms or
    // corrects them.
    static final List<String> CARD_PAYMENT_KEYWORDS = List.of(
            "payment received", "payment recd", "payment thank", "thank you", "bbps", "autopay", "auto debit");
    static final List<String> CARD_ADJUSTMENT_KEYWORDS = List.of(
            "waiver", "waived", "surcharge", "emi conversion", "converted to emi", "conv to emi");
    static final List<String> REWARD_KEYWORDS = List.of("cashback", "cash back", "reward");
    static final List<String> INVESTMENT_INFLOW_KEYWORDS = List.of(
            "redemption", "redeem", "fd closure", "fd maturity", "maturity proceeds", "iccl");
    static final List<String> LOAN_DRAWDOWN_KEYWORDS = List.of("loan disb", "disbursal", "disbursement");
    static final List<String> INTEREST_KEYWORDS = List.of("int pd", "interest", "int cr", "int credit", "sb int");

    private FlowClassifier() {}

    /** @param accountType the owning account's type; null is treated as a non-card account */
    public static FlowDecision classify(Transaction t, Account.Type accountType) {
        return classify(t, accountType, false);
    }

    /**
     * @param inUsersSalaryCategory whether the row sits in the user's own Salary category -- the
     *                              caller resolves the id, since this class never loads categories
     */
    public static FlowDecision classify(Transaction t, Account.Type accountType, boolean inUsersSalaryCategory) {
        return t.getTxnType() == Transaction.Type.EXPENSE ? outflow(t) : inflow(t, accountType, inUsersSalaryCategory);
    }

    private static FlowDecision outflow(Transaction t) {
        if (t.isTransfer()) return of(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER);
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.INVESTMENT_TRANSFER) {
            return of(FlowClass.INVESTMENT, FlowReason.INVESTMENT_CONTRIBUTION);
        }
        return of(FlowClass.EXPENSE, FlowReason.PURCHASE);
    }

    private static FlowDecision inflow(Transaction t, Account.Type accountType, boolean inUsersSalaryCategory) {
        if (t.isTransfer()) return of(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER);
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND) {
            return of(FlowClass.REFUND, FlowReason.LINKED_REFUND);
        }
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL) {
            return of(FlowClass.ADJUSTMENT, FlowReason.REVERSAL);
        }

        String description = t.getDescription();
        String text = " " + CategoryRules.normalize(description) + " ";

        // Before the refund word: an income-tax refund is income, not money back from a merchant.
        if (t.getCounterpartyType() == CounterpartyType.GOVERNMENT && ReconciliationService.looksLikeRefund(description)) {
            return of(FlowClass.INCOME, FlowReason.TAX_REFUND);
        }
        if (ReconciliationService.looksLikeReversal(description)) return of(FlowClass.ADJUSTMENT, FlowReason.REVERSAL);
        if (ReconciliationService.looksLikeRefund(description)) return of(FlowClass.REFUND, FlowReason.UNLINKED_REFUND);

        if (accountType == Account.Type.CREDIT_CARD) {
            // A card is a liability: a credit on it pays the debt down, gives money back, or rewards
            // spend. None of those is earned income, so an unexplained one is UNRESOLVED, never INCOME.
            if (hasAny(text, CARD_ADJUSTMENT_KEYWORDS)) return of(FlowClass.ADJUSTMENT, FlowReason.CARD_ADJUSTMENT);
            if (hasAny(text, REWARD_KEYWORDS)) return of(FlowClass.INCOME, FlowReason.REWARD);
            if (hasAny(text, CARD_PAYMENT_KEYWORDS)) return of(FlowClass.TRANSFER, FlowReason.CARD_PAYMENT_RECEIVED);
            return of(FlowClass.UNRESOLVED, FlowReason.CARD_UNEXPLAINED_CREDIT);
        }

        String suggested = CategoryRules.suggestCategory(description);
        if (ReconciliationService.INVESTMENTS_CATEGORY.equals(suggested) || hasAny(text, INVESTMENT_INFLOW_KEYWORDS)) {
            return of(FlowClass.INVESTMENT, FlowReason.INVESTMENT_WITHDRAWAL);
        }
        if (hasAny(text, LOAN_DRAWDOWN_KEYWORDS)) return of(FlowClass.LIABILITY, FlowReason.LOAN_DRAWDOWN);
        if ("Salary".equals(suggested)) return of(FlowClass.INCOME, FlowReason.SALARY);
        if (hasAny(text, INTEREST_KEYWORDS)) return of(FlowClass.INCOME, FlowReason.INTEREST);
        if (hasAny(text, REWARD_KEYWORDS)) return of(FlowClass.INCOME, FlowReason.REWARD);
        // The user's own word outranks the narration's shape, and only the person rule below needs
        // outranking: everything above it is a mechanism (a refund, a card, an investment, a loan)
        // the Income/Expense choice on the add form cannot express. Without these, a freelance fee
        // typed in by hand, or a person's UPI the user taught Fynora is their salary, would silently
        // leave income because the narration names a person.
        if (t.getSource() == Transaction.Source.MANUAL) return of(FlowClass.INCOME, FlowReason.USER_ENTERED);
        if (inUsersSalaryCategory && salaryCategoryIsTheUsersChoice(t)) return of(FlowClass.INCOME, FlowReason.SALARY);
        if (t.getCounterpartyType() == CounterpartyType.PERSON) return of(FlowClass.UNRESOLVED, FlowReason.PERSON_INFLOW);
        return of(FlowClass.INCOME, FlowReason.OTHER_INCOME);
    }

    /** A person, or a rule or pattern learned from them, put the row in Salary -- not a global rule
     *  or the AI fallback, which can guess Salary for a person's transfer. */
    private static boolean salaryCategoryIsTheUsersChoice(Transaction t) {
        if (t.isCategoryManuallySet()) return true;
        Transaction.DecisionSource source = t.getDecisionSource();
        return source == Transaction.DecisionSource.MANUAL
                || source == Transaction.DecisionSource.USER_RULE
                || source == Transaction.DecisionSource.LEARNED_PATTERN;
    }

    /** Word-START match on the space-padded normalised text: "reward" matches "rewards", but
     *  "disbursement" does not match "reimbursement". */
    static boolean hasAny(String paddedNormalized, List<String> keywords) {
        for (String k : keywords) {
            if (paddedNormalized.contains(" " + k)) return true;
        }
        return false;
    }

    private static FlowDecision of(FlowClass c, FlowReason r) { return new FlowDecision(c, r); }
}
