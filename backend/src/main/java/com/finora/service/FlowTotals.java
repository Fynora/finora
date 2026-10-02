package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one place an income total decides which rows count. Every income sum in the app used to
 * filter {@code txnType == INCOME} by hand -- in the dashboard, the monthly report, the range totals
 * and the analytics trend -- which is how "every credit is income" survived every fix to the
 * exclusions around it. Callers pass rows that have ALREADY been through
 * {@link RefundNetting#reportable}; this narrows them further by {@link FlowClassifier}.
 * IncomeSingleEntryPointTest keeps new screens from deciding income by hand again.
 */
public final class FlowTotals {

    private FlowTotals() {}

    /** What the classifier needs about the user beyond the row itself: each account's type (a card
     *  credit is never income), which category ids are the user's Salary category, and the user's
     *  inflow kinds (Plan 2). Production code builds this through InflowChoiceService.contextFor. */
    public record Context(Map<UUID, Account.Type> accountTypes, Set<UUID> salaryCategoryIds, InflowChoices choices,
                          Set<UUID> repaymentCategoryIds) {

        /** No repayment category -- for callers that predate it. */
        public Context(Map<UUID, Account.Type> accountTypes, Set<UUID> salaryCategoryIds, InflowChoices choices) {
            this(accountTypes, salaryCategoryIds, choices, Set.of());
        }
    }

    /** The seeded category a credit is filed under when a person paid the user back (AuthService). */
    static final String REPAYMENT_CATEGORY = "Friend Repayment";

    public static Context context(Collection<Account> accounts, Collection<Category> categories, InflowChoices choices) {
        Map<UUID, Account.Type> types = new HashMap<>();
        for (Account a : accounts) {
            if (a.getId() != null && a.getAccountType() != null) types.put(a.getId(), a.getAccountType());
        }
        // By name, case-insensitively: these are seeded system categories (AuthService), and a user
        // who deleted and recreated one still means the same thing.
        Set<UUID> salary = new HashSet<>();
        Set<UUID> repayment = new HashSet<>();
        for (Category c : categories) {
            if (c.getId() == null || c.getName() == null) continue;
            String name = c.getName().trim();
            if (name.equalsIgnoreCase("Salary")) salary.add(c.getId());
            if (name.equalsIgnoreCase(REPAYMENT_CATEGORY)) repayment.add(c.getId());
        }
        return new Context(types, salary, choices, repayment);
    }

    public static boolean countsAsIncome(Transaction t, Context ctx) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decision(t, ctx).flowClass() == FlowClassifier.FlowClass.INCOME;
    }

    public static boolean isUnresolvedInflow(Transaction t, Context ctx) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decision(t, ctx).flowClass() == FlowClassifier.FlowClass.UNRESOLVED;
    }

    /**
     * A credit that gives spend back without being matched to the purchase it reverses: a refund
     * or reversal the reconciliation pass could not link, or a card adjustment (a fee waiver, an
     * EMI conversion). It is not income, and before flow classification it was counted as income
     * -- which, by accident, kept net savings right. Now it offsets spend instead, in its own month
     * and category; see {@link RefundNetting#withUnlinkedOffsets}. A LINKED refund or reversal is
     * never one of these: RefundNetting already nets it off its own purchase.
     */
    public static boolean offsetsSpend(Transaction t, Context ctx) {
        if (t.getTxnType() != Transaction.Type.INCOME) return false;
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND
                || t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL) return false;
        FlowClassifier.FlowReason reason = decision(t, ctx).reason();
        return reason == FlowClassifier.FlowReason.UNLINKED_REFUND
                || reason == FlowClassifier.FlowReason.REVERSAL
                || reason == FlowClassifier.FlowReason.CARD_ADJUSTMENT;
        // PAID_BACK is deliberately not here (yet). Money a person paid back is filed under Friend
        // Repayment, where nothing was spent, and netting it against Personal Transfer as a whole
        // was measured (2026-10-02) to cancel unrelated spending and to disagree month by month.
        // It is matched per person to the payments it settles in a follow-up, once a person is
        // recognised as one counterparty across their payments.
    }

    /** Money that came in and that Fynora cannot yet say is income -- shown beside income, never in it. */
    public static BigDecimal unresolvedInflow(Collection<Transaction> reportable, Context ctx) {
        return reportable.stream().filter(t -> isUnresolvedInflow(t, ctx))
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** How many rows {@link #unresolvedInflow} summed -- the "N transactions need classification" count. */
    public static int unresolvedInflowCount(Collection<Transaction> reportable, Context ctx) {
        return (int) reportable.stream().filter(t -> isUnresolvedInflow(t, ctx)).count();
    }

    /** The reason carrying the most unresolved VALUE -- the banner's "Mostly money from people". Null when none. */
    public static FlowClassifier.FlowReason unresolvedTopReason(Collection<Transaction> reportable,
                                                                Context ctx) {
        Map<FlowClassifier.FlowReason, BigDecimal> byReason = new EnumMap<>(FlowClassifier.FlowReason.class);
        for (Transaction t : reportable) {
            if (!isUnresolvedInflow(t, ctx)) continue;
            byReason.merge(decision(t, ctx).reason(), t.getAmount(), BigDecimal::add);
        }
        // Ties break on enum declaration order, which EnumMap iterates in -- deterministic.
        FlowClassifier.FlowReason top = null;
        BigDecimal topValue = null;
        for (Map.Entry<FlowClassifier.FlowReason, BigDecimal> e : byReason.entrySet()) {
            if (topValue == null || e.getValue().compareTo(topValue) > 0) {
                top = e.getKey();
                topValue = e.getValue();
            }
        }
        return top;
    }

    /** The flow reading every total in this class uses, exposed for the counts-as endpoint. */
    public static FlowClassifier.FlowDecision decision(Transaction t, Context ctx) {
        InflowChoices.Chosen chosen = ctx.choices().chosenFor(t);
        return FlowClassifier.classify(t,
                t.getAccountId() == null ? null : ctx.accountTypes().get(t.getAccountId()),
                categoryRole(t, ctx),
                chosen == null ? null : chosen.kind());
    }

    private static FlowClassifier.CategoryRole categoryRole(Transaction t, Context ctx) {
        UUID id = t.getCategoryId();
        if (id == null) return FlowClassifier.CategoryRole.NONE;
        if (ctx.salaryCategoryIds().contains(id)) return FlowClassifier.CategoryRole.SALARY;
        if (ctx.repaymentCategoryIds().contains(id)) return FlowClassifier.CategoryRole.REPAYMENT;
        return FlowClassifier.CategoryRole.NONE;
    }

    /** The user's kind for this row (its own, else its sender's), or null. */
    public static InflowChoices.Chosen chosen(Transaction t, Context ctx) {
        return ctx.choices().chosenFor(t);
    }

    /** The line an income row is reported under: the user's kind when one made it income,
     *  otherwise what the automatic reading found. Only meaningful for a row countsAsIncome accepts. */
    public static String incomeLabel(Transaction t, Context ctx) {
        FlowClassifier.FlowDecision d = decision(t, ctx);
        if (d.reason() == FlowClassifier.FlowReason.USER_KIND || d.reason() == FlowClassifier.FlowReason.FAMILY_SUPPORT) {
            return ctx.choices().chosenFor(t).kind().getName();
        }
        return switch (d.reason()) {
            case SALARY -> "Salary";
            case INTEREST -> "Interest";
            case DIVIDEND -> "Dividends";
            case REWARD -> "Rewards and cashback";
            case TAX_REFUND -> "Tax refunds";
            default -> "Other income";
        };
    }
}
