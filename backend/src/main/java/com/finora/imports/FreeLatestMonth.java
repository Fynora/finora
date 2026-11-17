package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * "Import only the latest month" (Sid, 2026-11-17): what a Free-plan customer may import from a
 * statement longer than one month -- the statement's last FULL calendar month -- and that month's
 * own opening and closing balance, read off the statement's running balance.
 *
 * <h2>Which month</h2>
 *
 * <p>The last calendar month the statement wholly covers. Coverage is the printed period when both
 * ends are printed; otherwise the first to last transaction date. A statement for 17 Aug to 17 Nov
 * gives October; one ending 31 Oct gives October. Without a printed period the true end is not
 * known, so a month counts as covered only up to its last transaction: a CSV whose last row is on
 * 29 Oct gives September, not a guessed October. A wholly covered month with no transactions is
 * passed over for the one before it -- a printed period can overstate the data (a Slice statement
 * prints its whole financial year), and a month can have no activity -- so the offer is the latest
 * whole month with something to import. None when no month is wholly covered (2 Jan to 8 Feb
 * covers neither) or none of the covered months has a transaction.
 *
 * <h2>The month's balances</h2>
 *
 * <p>Only when every transaction in the month carries a running balance, and only when they agree:
 * the opening is the balance before the month's first transaction, the closing the balance after
 * its last, each found from the balance chain rather than file order ({@link BalanceChainUtil} --
 * file order within a day is not reliable), and opening plus the month's movements must equal the
 * closing to the paisa. Anything else gives no balances, and the import claims none (a card
 * statement whose balance runs the other way fails that check, which is the safe outcome): a wrong
 * balance in someone's records is worse than none.
 */
public final class FreeLatestMonth {

    private FreeLatestMonth() {}

    /** A whole calendar month, first to last day. */
    public record Month(LocalDate from, LocalDate to) {
        boolean contains(LocalDate date) {
            return date != null && !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /** The month's own figures; either balance null when the statement cannot vouch for it. */
    public record Figures(BigDecimal openingBalance, BigDecimal closingBalance) {
        static final Figures NONE = new Figures(null, null);
    }

    /** The statement's latest wholly covered calendar month with at least one transaction, or null. */
    public static Month lastFullMonth(LocalDate printedStart, LocalDate printedEnd, List<StagedRow> rows) {
        List<LocalDate> dates = rows == null ? List.of()
                : rows.stream().map(StagedRow::date).filter(Objects::nonNull).toList();
        LocalDate start;
        LocalDate end;
        if (printedStart != null && printedEnd != null) {
            start = printedStart.isAfter(printedEnd) ? printedEnd : printedStart;
            end = printedStart.isAfter(printedEnd) ? printedStart : printedEnd;
        } else if (!dates.isEmpty()) {
            start = dates.stream().min(Comparator.naturalOrder()).orElseThrow();
            end = dates.stream().max(Comparator.naturalOrder()).orElseThrow();
        } else {
            return null;
        }
        YearMonth month = YearMonth.from(end);
        if (!end.equals(month.atEndOfMonth())) month = month.minusMonths(1);
        // The latest wholly covered month WITH transactions: a printed period can overstate the data
        // (a Slice statement prints its whole financial year), and a month can have no activity.
        while (!start.isAfter(month.atDay(1))) {
            Month candidate = new Month(month.atDay(1), month.atEndOfMonth());
            if (dates.stream().anyMatch(candidate::contains)) return candidate;
            month = month.minusMonths(1);
        }
        return null;
    }

    /** The month's opening and closing balance from the staged running balances; see the class doc. */
    public static Figures figures(List<StagedRow> rows, Month month) {
        List<Link> inMonth = new ArrayList<>();
        for (StagedRow row : rows) {
            if (!month.contains(row.date())) continue;
            BigDecimal signed = signed(row);
            if (row.balanceAfter() == null || signed == null) return Figures.NONE;
            inMonth.add(new Link(row.date(), signed, row.balanceAfter()));
        }
        if (inMonth.isEmpty()) return Figures.NONE;
        LocalDate firstDay = inMonth.stream().map(Link::date).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate lastDay = inMonth.stream().map(Link::date).max(Comparator.naturalOrder()).orElseThrow();
        Link first = BalanceChainUtil.first(inMonth.stream().filter(l -> l.date().equals(firstDay)).toList());
        Link last = BalanceChainUtil.last(inMonth.stream().filter(l -> l.date().equals(lastDay)).toList());
        BigDecimal opening = first.balanceAfter().subtract(first.signedAmount());
        BigDecimal closing = last.balanceAfter();
        BigDecimal moved = inMonth.stream().map(Link::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (opening.add(moved).compareTo(closing) != 0) return Figures.NONE;
        return new Figures(opening, closing);
    }

    /** Positive for money in, negative for money out; null for a row whose direction is unknown. */
    private static BigDecimal signed(StagedRow row) {
        if (row.amount() == null || row.type() == null) return null;
        BigDecimal amount = row.amount().abs();
        return switch (row.type()) {
            case "INCOME" -> amount;
            case "EXPENSE" -> amount.negate();
            default -> null;
        };
    }

    private record Link(LocalDate date, BigDecimal signedAmount, BigDecimal balanceAfter)
            implements BalanceChainUtil.ChainLink {}
}
