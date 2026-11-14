package com.finora.transactions;

import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * How one payee is paid, for the line a Quick sort question shows under the payee's name ("14 payments
 * since Aug · usually ₹150–₹400") and the payments that line opens. Spec:
 * docs/superpowers/specs/2026-11-14-smarter-person-question-design.md.
 *
 * <p>"Usually" is a presentation choice, not a threshold that files anything: with 4 or more payments it
 * is the nearest-rank 25th to 75th percentile, so one large or tiny payment does not stretch it; with 2
 * or 3 it is the lowest to the highest; a single payment has none. Absolute amounts, rounded half up to
 * whole rupees, like other lists in the app.
 *
 * @param recent the latest payments, newest first, at most {@link #MAX_RECENT}
 */
public record PayeePattern(int payments, LocalDate firstDate, LocalDate latestDate, BigDecimal typicalLow,
                           BigDecimal typicalHigh, List<QuickSortDto.Sample> recent) {

    static final int MAX_RECENT = 10;

    /** The pattern of these payments, given in any order. */
    public static PayeePattern of(List<Transaction> payments) {
        List<Transaction> newestFirst = payments.stream()
                .sorted(Comparator.comparing(Transaction::getTxnDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<BigDecimal> amounts = payments.stream().map(t -> t.getAmount().abs()).sorted().toList();
        int n = amounts.size();
        BigDecimal low = null;
        BigDecimal high = null;
        if (n >= 4) {
            low = amounts.get(nearestRank(0.25, n));
            high = amounts.get(nearestRank(0.75, n));
        } else if (n >= 2) {
            low = amounts.get(0);
            high = amounts.get(n - 1);
        }
        LocalDate first = newestFirst.isEmpty() ? null : newestFirst.get(newestFirst.size() - 1).getTxnDate();
        LocalDate latest = newestFirst.isEmpty() ? null : newestFirst.get(0).getTxnDate();
        List<QuickSortDto.Sample> recent = newestFirst.stream().limit(MAX_RECENT)
                .map(t -> new QuickSortDto.Sample(t.getId(), t.getTxnDate(), t.getDescription(), t.getAmount(),
                        t.getTxnType().name()))
                .toList();
        return new PayeePattern(n, first, latest, rupees(low), rupees(high), recent);
    }

    /** Zero-based index of the nearest-rank percentile: ceil(p * n) - 1. */
    private static int nearestRank(double p, int n) {
        return Math.max(0, (int) Math.ceil(p * n) - 1);
    }

    private static BigDecimal rupees(BigDecimal amount) {
        return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP);
    }
}
