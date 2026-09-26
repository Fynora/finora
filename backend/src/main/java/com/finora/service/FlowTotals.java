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
     *  credit is never income) and which category ids are the user's Salary category. */
    public record Context(Map<UUID, Account.Type> accountTypes, Set<UUID> salaryCategoryIds) {}

    public static Context context(Collection<Account> accounts, Collection<Category> categories) {
        Map<UUID, Account.Type> types = new HashMap<>();
        for (Account a : accounts) {
            if (a.getId() != null && a.getAccountType() != null) types.put(a.getId(), a.getAccountType());
        }
        // By name, case-insensitively: "Salary" is the seeded system category (AuthService), and a
        // user who deleted and recreated it still means the same thing.
        Set<UUID> salary = new HashSet<>();
        for (Category c : categories) {
            if (c.getId() != null && c.getName() != null && c.getName().trim().equalsIgnoreCase("Salary")) salary.add(c.getId());
        }
        return new Context(types, salary);
    }

    public static boolean countsAsIncome(Transaction t, Context ctx) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decide(t, ctx).flowClass() == FlowClassifier.FlowClass.INCOME;
    }

    public static boolean isUnresolvedInflow(Transaction t, Context ctx) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decide(t, ctx).flowClass() == FlowClassifier.FlowClass.UNRESOLVED;
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
        FlowClassifier.FlowReason reason = decide(t, ctx).reason();
        return reason == FlowClassifier.FlowReason.UNLINKED_REFUND
                || reason == FlowClassifier.FlowReason.REVERSAL
                || reason == FlowClassifier.FlowReason.CARD_ADJUSTMENT;
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
            byReason.merge(decide(t, ctx).reason(), t.getAmount(), BigDecimal::add);
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

    private static FlowClassifier.FlowDecision decide(Transaction t, Context ctx) {
        return FlowClassifier.classify(t,
                t.getAccountId() == null ? null : ctx.accountTypes().get(t.getAccountId()),
                t.getCategoryId() != null && ctx.salaryCategoryIds().contains(t.getCategoryId()));
    }
}
