package com.finora.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Which way a statement lists its transactions: oldest-first, as most banks print, or newest-first,
 * as Punjab National Bank (PNB ONE) and Bandhan statements do. A newest-first statement prints each
 * DAY newest-first too, so its true order is the print order reversed.
 *
 * <p>Read from the dates first: more steps back in time than forward between consecutive printed
 * rows. When the dates say nothing (one day, or as many steps each way), from the running balance:
 * same-day neighbours whose balances only follow when read bottom-up. When neither says
 * newest-first, the statement is taken as printed.
 *
 * <p>Measured on the 47-statement local corpus: the four sections this reads as newest-first are
 * exactly the four whose same-day pairs chain only bottom-up (125 pairs, none top-down); in every
 * other section all 1,250 same-day pairs that chain one way only chain top-down. In production the
 * five PDF statements it reads as newest-first are the five whose same-day rows were saved reversed.
 *
 * <p>Shared by the import ({@code PdfPreviewGenerator}) and the repair of rows saved before it
 * ({@code com.finora.service.StatementRowOrderRepairSweepService}), so the two cannot disagree.
 */
public final class PrintedOrder {

    private PrintedOrder() {}

    /** One printed row: its date, what it moved the balance by (signed), and the balance after it. */
    public record Row(LocalDate date, BigDecimal signedAmount, BigDecimal balanceAfter) {}

    /** Whether {@code rows}, in the order they were printed, are listed newest-first. */
    public static boolean newestFirst(List<Row> rows) {
        int back = 0, forward = 0;
        LocalDate previous = null;
        for (Row row : rows) {
            if (row.date() == null) continue;
            if (previous != null) {
                int step = row.date().compareTo(previous);
                if (step < 0) back++;
                else if (step > 0) forward++;
            }
            previous = row.date();
        }
        if (back != forward) return back > forward;
        int topDown = 0, bottomUp = 0;
        for (int i = 1; i < rows.size(); i++) {
            Row above = rows.get(i - 1), below = rows.get(i);
            if (above.date() == null || !above.date().equals(below.date())) continue;
            if (above.balanceAfter() == null || below.balanceAfter() == null
                    || above.signedAmount() == null || below.signedAmount() == null) continue;
            boolean down = below.balanceAfter().subtract(above.balanceAfter()).compareTo(below.signedAmount()) == 0;
            boolean up = above.balanceAfter().subtract(below.balanceAfter()).compareTo(above.signedAmount()) == 0;
            if (down && !up) topDown++;
            else if (up && !down) bottomUp++;
        }
        return bottomUp > topDown;
    }
}
