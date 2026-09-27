package com.finora.imports;

import com.finora.dto.ImportDto.UnparseableRow;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recognises a payment app's own payment history -- today, Paytm's "Passbook Payments History" --
 * among the lines a document yielded when no transaction was extracted from it. Audit F-08.
 *
 * <h2>Why it is refused rather than imported</h2>
 *
 * The document is not a statement of any one account. Each row names the bank account the payment
 * was made from, and a single history spans several of them, while an import lands in exactly one
 * account. Every payment in it also appears in the statement of the bank it was paid from, so
 * importing it would duplicate rows the user gets from those statements. Decided on 2026-09-27:
 * recognise it and say so plainly.
 *
 * <h2>Why two headings, and why only when nothing was extracted</h2>
 *
 * Both the statement title ("Paytm Statement for ...") and the table heading ("Passbook Payments
 * History") must be present. Across the 33-document corpus the two appear together in the Paytm
 * history and in no other document, including the HDFC Paytm co-branded credit card statement,
 * which prints neither. {@link ExtractionCheck} only consults this after zero rows were staged, so
 * a statement that parses is never turned away by it.
 */
final class PaymentAppHistoryDetector {

    private PaymentAppHistoryDetector() {
    }

    private static final Pattern PAYTM_STATEMENT_TITLE =
            Pattern.compile("(?i)\\bpaytm\\s+statement\\s+for\\b");
    private static final Pattern PASSBOOK_PAYMENTS_HISTORY =
            Pattern.compile("(?i)\\bpassbook\\s+payments\\s+history\\b");

    static boolean isPaytmPaymentHistory(List<UnparseableRow> recovered) {
        if (recovered == null || recovered.isEmpty()) return false;
        boolean title = false;
        boolean table = false;
        for (UnparseableRow row : recovered) {
            String text = textOf(row);
            if (text == null) continue;
            title |= PAYTM_STATEMENT_TITLE.matcher(text).find();
            table |= PASSBOOK_PAYMENTS_HISTORY.matcher(text).find();
            if (title && table) return true;
        }
        return false;
    }

    private static String textOf(UnparseableRow row) {
        if (row == null || row.raw() == null) return null;
        Map<String, String> raw = row.raw();
        // A PDF's recovered line arrives as one "text" cell; a CSV's as several named cells. Every
        // cell is read, so the headings are found whichever shape the line came in.
        return String.join(" ", raw.values().stream().filter(v -> v != null).toList());
    }
}
