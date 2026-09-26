package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
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

    public static Map<UUID, Account.Type> accountTypes(Collection<Account> accounts) {
        Map<UUID, Account.Type> types = new HashMap<>();
        for (Account a : accounts) {
            if (a.getId() != null && a.getAccountType() != null) types.put(a.getId(), a.getAccountType());
        }
        return types;
    }

    public static boolean countsAsIncome(Transaction t, Map<UUID, Account.Type> accountTypes) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decide(t, accountTypes).flowClass() == FlowClassifier.FlowClass.INCOME;
    }

    public static boolean isUnresolvedInflow(Transaction t, Map<UUID, Account.Type> accountTypes) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decide(t, accountTypes).flowClass() == FlowClassifier.FlowClass.UNRESOLVED;
    }

    /** Money that came in and that Fynora cannot yet say is income -- shown beside income, never in it. */
    public static BigDecimal unresolvedInflow(Collection<Transaction> reportable, Map<UUID, Account.Type> accountTypes) {
        return reportable.stream().filter(t -> isUnresolvedInflow(t, accountTypes))
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** How many rows {@link #unresolvedInflow} summed -- the "N transactions need classification" count. */
    public static int unresolvedInflowCount(Collection<Transaction> reportable, Map<UUID, Account.Type> accountTypes) {
        return (int) reportable.stream().filter(t -> isUnresolvedInflow(t, accountTypes)).count();
    }

    /** The reason carrying the most unresolved VALUE -- the banner's "Mostly money from people". Null when none. */
    public static FlowClassifier.FlowReason unresolvedTopReason(Collection<Transaction> reportable,
                                                                Map<UUID, Account.Type> accountTypes) {
        Map<FlowClassifier.FlowReason, BigDecimal> byReason = new EnumMap<>(FlowClassifier.FlowReason.class);
        for (Transaction t : reportable) {
            if (!isUnresolvedInflow(t, accountTypes)) continue;
            byReason.merge(decide(t, accountTypes).reason(), t.getAmount(), BigDecimal::add);
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

    private static FlowClassifier.FlowDecision decide(Transaction t, Map<UUID, Account.Type> accountTypes) {
        return FlowClassifier.classify(t, t.getAccountId() == null ? null : accountTypes.get(t.getAccountId()));
    }
}
