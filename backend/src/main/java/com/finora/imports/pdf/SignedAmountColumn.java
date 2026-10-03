package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SIGNED_AMOUNT_COLUMN: a ledger whose one AMOUNT column carries the direction in its own sign --
 * debits printed "-₹1,954.00", credits printed unsigned "₹6,000.00".
 *
 * <h2>The defect this exists for</h2>
 *
 * {@code TransactionNormalizer} reads an unsigned, unmarked amount as an expense: right for the card
 * statements that print every purchase positive, and wrong here. Measured on a real slice small
 * finance bank savings statement: every credit -- a ₹6,000 transfer in, a refund, the daily interest
 * -- staged as an expense, and the balance chain failed on every one of them.
 *
 * <h2>Why the running balance decides, not the minus sign</h2>
 *
 * A minus is not one convention: a card statement can print its REFUNDS negative and its purchases
 * positive, the exact opposite of this ledger. What is not ambiguous is the statement's own running
 * balance. The column is read as signed only when taking each amount exactly as printed --
 * positive in, negative out -- reconciles the printed balance on at least
 * {@value #MIN_RECONCILED_SHARE_PERCENT}% of consecutive row pairs (and at least
 * {@value #MIN_PAIRS} of them), with at least one amount printed negative and at least one printed
 * unsigned. Then, and only then, each unsigned amount is marked "+" -- the leading-plus credit form
 * the normalizer already reads ({@code LEADING_PLUS_CREDIT}). A statement with a debit/credit column
 * pair, a Dr/Cr marker, a type column, or no running balance is returned untouched.
 */
final class SignedAmountColumn {

    static final int MIN_PAIRS = 3;
    static final int MIN_RECONCILED_SHARE_PERCENT = 90;

    private SignedAmountColumn() {}

    static List<Map<String, String>> markCredits(List<Map<String, String>> rows, DocumentContext ctx) {
        String amountKey = null, balanceKey = null;
        for (Map<String, String> row : rows) {
            for (String key : row.keySet()) {
                if (key == null) continue;
                String name = CsvParser.normalizeHeaderCell(key);
                if (name.equalsIgnoreCase("amount")) amountKey = key;
                else if (name.equalsIgnoreCase("balance")) balanceKey = key;
                else if (isDirectionColumn(name)) return rows;
            }
        }
        if (amountKey == null || balanceKey == null) return rows;

        BigDecimal previousBalance = null;
        int pairs = 0, reconciled = 0;
        boolean anyNegative = false, anyUnsigned = false;
        for (Map<String, String> row : rows) {
            String amountRaw = row.get(amountKey);
            BigDecimal amount = amountRaw == null ? null : CsvParser.parseNumeric(amountRaw);
            BigDecimal balance = row.get(balanceKey) == null ? null : CsvParser.parseNumeric(row.get(balanceKey));
            if (amount == null || balance == null) {
                previousBalance = null;
                continue;
            }
            String trimmed = amountRaw.trim();
            if (trimmed.startsWith("+") || CsvParser.hasTrailingDrCrMarker(trimmed)) return rows;
            if (amount.signum() < 0) anyNegative = true;
            else if (amount.signum() > 0) anyUnsigned = true;
            if (previousBalance != null) {
                pairs++;
                if (previousBalance.add(amount).compareTo(balance) == 0) reconciled++;
            }
            previousBalance = balance;
        }
        if (!anyNegative || !anyUnsigned || pairs < MIN_PAIRS
                || reconciled * 100 < pairs * MIN_RECONCILED_SHARE_PERCENT) {
            return rows;
        }

        List<Map<String, String>> marked = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            String amountRaw = row.get(amountKey);
            BigDecimal amount = amountRaw == null ? null : CsvParser.parseNumeric(amountRaw);
            if (amount == null || amount.signum() <= 0) {
                marked.add(row);
                continue;
            }
            Map<String, String> copy = new LinkedHashMap<>(row);
            copy.put(amountKey, "+" + amountRaw.trim());
            marked.add(copy);
        }
        if (ctx != null) ctx.record("SIGNED_AMOUNT_COLUMN");
        return marked;
    }

    /** A column that already says which way the money moved -- the normalizer reads those first. */
    private static boolean isDirectionColumn(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("debit") || n.equals("credit") || n.contains("withdrawal") || n.contains("deposit")
                || n.equals("dr amount") || n.equals("cr amount") || n.equals("debit amount")
                || n.equals("credit amount") || n.equals("type") || n.contains("dr/cr") || n.contains("dr / cr")
                || n.contains("cr/dr") || n.contains("cr / dr");
    }
}
