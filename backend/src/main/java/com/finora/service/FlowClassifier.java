package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.InflowKind;
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
    // 3: a tax refund is recognised from its narration too, not only from a GOVERNMENT counterparty.
    // 4: a dividend or IDCW payout is income, even when the narration names a mutual fund.
    // 5: a kind the user chose for the row or its sender (Plan 2) outranks every automatic rule
    //    except a pairing reconciliation made (transfer, linked refund, linked reversal).
    // 6: coverage from the corpus -- a cash deposit, a merchant's UPI credit and a UPI credit from an
    //    unrecognised sender are left for the user instead of counted as income; a card "CC PAYMENT"
    //    is a bill payment; a clearing-corporation payout is an investment even when wrapped mid-word.
    // 7: a credit the user filed under Friend Repayment themselves is money paid back, the same as
    //    choosing the "Paid back to me" kind.
    public static final short VERSION = 7;

    public enum FlowClass { INCOME, EXPENSE, REFUND, TRANSFER, INVESTMENT, LIABILITY, ADJUSTMENT, UNRESOLVED }

    public enum FlowReason {
        SALARY, INTEREST, DIVIDEND, REWARD, TAX_REFUND, OTHER_INCOME, USER_ENTERED,
        USER_KIND, FAMILY_SUPPORT,
        PURCHASE,
        LINKED_REFUND, UNLINKED_REFUND, REVERSAL, CARD_ADJUSTMENT, PAID_BACK, USER_KIND_EXCLUDED,
        OWN_ACCOUNT_TRANSFER, USER_OWN_MONEY, CARD_PAYMENT_RECEIVED,
        INVESTMENT_CONTRIBUTION, INVESTMENT_WITHDRAWAL,
        LOAN_DRAWDOWN,
        PERSON_INFLOW, CARD_UNEXPLAINED_CREDIT, CASH_DEPOSIT, MERCHANT_CREDIT, UNKNOWN_SENDER
    }

    public record FlowDecision(FlowClass flowClass, FlowReason reason) {}

    // Word-start matches against CategoryRules.normalize output (lower-case, punctuation -> space).
    // Initial lists; the corpus probe (FlowClassCorpusProbe) is the evidence that confirms or
    // corrects them.
    static final List<String> CARD_PAYMENT_KEYWORDS = List.of(
            "payment received", "payment recd", "payment thank", "thank you", "bbps", "autopay", "auto debit", "cc payment");
    static final List<String> CARD_ADJUSTMENT_KEYWORDS = List.of(
            "waiver", "waived", "surcharge", "emi conversion", "converted to emi", "conv to emi");
    static final List<String> REWARD_KEYWORDS = List.of("cashback", "cash back", "reward");
    static final List<String> INVESTMENT_INFLOW_KEYWORDS = List.of(
            "redemption", "redeem", "fd closure", "fd maturity", "maturity proceeds", "iccl");
    /** Share-sale proceeds. Compared with the spaces taken out: a wrapped narration splits the name
     *  mid-word ("INDIAN C LEARING CORPORATION"). Only the long phrase -- a short one like "iccl"
     *  would also match across two words ("UPI CCLUB"); the word-start "iccl" is in the list above. */
    static final List<String> CLEARING_CORPORATION_COMPACT = List.of("clearingcorporation");
    /** Cash paid into the account: the user's own money, a loan repaid, or takings -- they say which.
     *  Each ends at a word (trailing space on the padded text): "by cash" alone matched "BY CASHFREE",
     *  a payment gateway. */
    static final List<String> CASH_DEPOSIT_KEYWORDS = List.of("by cash ", "cash deposit", "cash dep ");
    static final List<String> LOAN_DRAWDOWN_KEYWORDS = List.of("loan disb", "disbursal", "disbursement");
    /** Earned ON an investment, so income -- checked before the investment rule, which "mutual fund" would match. */
    static final List<String> DIVIDEND_KEYWORDS = List.of("dividend", "idcw");
    static final List<String> INTEREST_KEYWORDS = List.of("int pd", "interest", "int cr", "int credit", "sb int");
    /** A refund from the tax department. Read from the narration as well as the stored counterparty:
     *  "TAX REFUND CPC ..." names no government body, and "ECS CR INCOME TAX ..." is typed by its
     *  rail word first, so the counterparty alone missed both. "itd " ends at a word so ITDC does not match. */
    static final List<String> TAX_REFUND_KEYWORDS = List.of("tax refund", "income tax", "incometax", "itd ", "cbdt");

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
        return classify(t, accountType, inUsersSalaryCategory, null);
    }

    /**
     * @param chosen the kind the user gave this row or its sender (InflowChoices.chosenFor), or null.
     *               A debit ignores it: kinds describe money coming in.
     */
    public static FlowDecision classify(Transaction t, Account.Type accountType, boolean inUsersSalaryCategory,
                                        InflowKind chosen) {
        return classify(t, accountType, inUsersSalaryCategory ? CategoryRole.SALARY : CategoryRole.NONE, chosen);
    }

    /**
     * What the row's category means for a credit. The caller resolves it from the category id, since
     * this class never loads categories. It only counts when the category is the user's own choice
     * (see {@link #categoryIsTheUsersChoice}); a category Fynora guessed answers nothing.
     */
    public enum CategoryRole { NONE, SALARY, REPAYMENT }

    public static FlowDecision classify(Transaction t, Account.Type accountType, CategoryRole categoryRole,
                                        InflowKind chosen) {
        return t.getTxnType() == Transaction.Type.EXPENSE
                ? outflow(t) : inflow(t, accountType, categoryRole == null ? CategoryRole.NONE : categoryRole, chosen);
    }

    private static FlowDecision outflow(Transaction t) {
        if (t.isTransfer()) return of(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER);
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.INVESTMENT_TRANSFER) {
            return of(FlowClass.INVESTMENT, FlowReason.INVESTMENT_CONTRIBUTION);
        }
        return of(FlowClass.EXPENSE, FlowReason.PURCHASE);
    }

    private static FlowDecision inflow(Transaction t, Account.Type accountType, CategoryRole categoryRole,
                                       InflowKind chosen) {
        if (t.isTransfer()) return of(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER);
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND) {
            return of(FlowClass.REFUND, FlowReason.LINKED_REFUND);
        }
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL) {
            return of(FlowClass.ADJUSTMENT, FlowReason.REVERSAL);
        }
        // The user's own answer outranks every rule below -- but not a pairing reconciliation made
        // above, which has its own undo ("not a transfer").
        if (chosen != null) return byKind(chosen);
        // Filing a credit under Friend Repayment is the same answer as the "Paid back to me" kind,
        // and it outranks the narration rules below for the same reason the kind does.
        if (categoryRole == CategoryRole.REPAYMENT && categoryIsTheUsersChoice(t)) {
            return of(FlowClass.ADJUSTMENT, FlowReason.PAID_BACK);
        }

        String description = t.getDescription();
        String text = " " + CategoryRules.normalize(description) + " ";

        // Before the refund word: an income-tax refund is income, not money back from a merchant.
        if (looksLikeTaxRefund(t)) return of(FlowClass.INCOME, FlowReason.TAX_REFUND);
        if (ReconciliationService.looksLikeReversal(description)) return of(FlowClass.ADJUSTMENT, FlowReason.REVERSAL);
        // A narration cut off mid-word ("... R02 PHONEPE REVERS"). Read here and not by the
        // reconciliation pass: on the corpus that pass linked both such credits to unrelated
        // purchases, taking money off spend that was never refunded. The cut-off word exactly
        // (trailing space on the padded text): "REVERSE SWEEP" is a deposit coming back, not a
        // payment reversed, and a REVERSAL reason takes the amount off spend.
        if (hasAny(text, List.of("revers "))) return of(FlowClass.ADJUSTMENT, FlowReason.REVERSAL);
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
        if (hasAny(text, DIVIDEND_KEYWORDS)) return of(FlowClass.INCOME, FlowReason.DIVIDEND);
        if (ReconciliationService.INVESTMENTS_CATEGORY.equals(suggested) || hasAny(text, INVESTMENT_INFLOW_KEYWORDS)
                || CLEARING_CORPORATION_COMPACT.stream().anyMatch(text.replace(" ", "")::contains)) {
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
        if (categoryRole == CategoryRole.SALARY && categoryIsTheUsersChoice(t)) return of(FlowClass.INCOME, FlowReason.SALARY);
        if (t.getCounterpartyType() == CounterpartyType.PERSON) return of(FlowClass.UNRESOLVED, FlowReason.PERSON_INFLOW);
        // Below: shapes the corpus showed counted as income with nothing saying they were earned.
        // Each is left for the user to name rather than guessed either way.
        if (hasAny(text, CASH_DEPOSIT_KEYWORDS)) return of(FlowClass.UNRESOLVED, FlowReason.CASH_DEPOSIT);
        if (hasAny(text, List.of("upi"))) {
            // A shop paying back over UPI with no refund word: a refund, a seller's payout or a
            // cashback. NEFT/IMPS from a company stays income -- that is how employers and clients pay.
            if (t.getCounterpartyType() == CounterpartyType.BUSINESS) {
                return of(FlowClass.UNRESOLVED, FlowReason.MERCHANT_CREDIT);
            }
            // A one-word name or a bare phone-number VPA: neither a person nor a shop can be told.
            if (t.getCounterpartyType() == null || t.getCounterpartyType() == CounterpartyType.UNKNOWN) {
                return of(FlowClass.UNRESOLVED, FlowReason.UNKNOWN_SENDER);
            }
        }
        return of(FlowClass.INCOME, FlowReason.OTHER_INCOME);
    }

    private static FlowDecision byKind(InflowKind k) {
        if (k.getBuiltIn() != null) {
            return switch (k.getBuiltIn()) {
                case INCOME -> of(FlowClass.INCOME, FlowReason.USER_KIND);
                case FAMILY_SUPPORT -> of(FlowClass.INCOME, FlowReason.FAMILY_SUPPORT);
                case OWN_MONEY -> of(FlowClass.TRANSFER, FlowReason.USER_OWN_MONEY);
                case PAID_BACK -> of(FlowClass.ADJUSTMENT, FlowReason.PAID_BACK);
                // Gives spend back in its own month and category, like any unlinked refund
                // (FlowTotals.offsetsSpend reads this reason).
                case REFUND -> of(FlowClass.REFUND, FlowReason.UNLINKED_REFUND);
            };
        }
        return k.isCountsAsIncome()
                ? of(FlowClass.INCOME, FlowReason.USER_KIND)
                : of(FlowClass.ADJUSTMENT, FlowReason.USER_KIND_EXCLUDED);
    }

    /** A credit that says "refund" and comes from the tax department. The reconciliation refund
     *  pass reads this too, so a tax refund is never linked to a purchase it did not refund. */
    static boolean looksLikeTaxRefund(Transaction t) {
        if (!ReconciliationService.looksLikeRefund(t.getDescription())) return false;
        return t.getCounterpartyType() == CounterpartyType.GOVERNMENT
                || hasAny(" " + CategoryRules.normalize(t.getDescription()) + " ", TAX_REFUND_KEYWORDS);
    }

    /** A person, or a rule or pattern learned from them, put the row in its category -- not a global
     *  rule or the AI fallback, which can guess Salary for a person's transfer. */
    private static boolean categoryIsTheUsersChoice(Transaction t) {
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
