package com.finora.imports;

import com.finora.dto.ImportDto.UnparseableRow;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recognises a payment app's own payment history -- Paytm's "Passbook Payments History", and a UPI
 * app's transaction history in the "Paid to / Received from" row grammar -- so it can be refused as
 * the wrong kind of document. Audit F-08.
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
 * which prints neither.
 *
 * <p>Checked in two places. The PDF generator runs {@link #isPaymentAppHistory} over the acquired
 * text and records the fact on {@link DocumentContext}, and {@link ExtractionCheck} refuses such a
 * document even when a table reader staged rows from it: a longer history than the one in the
 * corpus could reach one, and its rows would still span several accounts. When no context flag is
 * available, the lines set aside after a zero-row extraction are checked for the Paytm headings
 * instead. The UPI row grammar below is only checked on the PDF's own text runs, because it depends
 * on where each run begins, which a recovered line no longer shows.
 *
 * <p>Refusing at staging is what keeps such a file out of the trust review queue: it used to be
 * staged as hundreds of rows, all of one direction and with no balance, and held for days before
 * anyone could tell the user it was the wrong document.
 */
public final class PaymentAppHistoryDetector {

    private PaymentAppHistoryDetector() {
    }

    /** Either kind of payment app history this class recognises, from the document's own text runs. */
    public static boolean isPaymentAppHistory(List<String> texts) {
        return containsBothHeadings(texts) || hasUpiAppRowGrammar(texts);
    }

    private static final Pattern PAYTM_STATEMENT_TITLE =
            Pattern.compile("(?i)\\bpaytm\\s+statement\\s+for\\b");
    private static final Pattern PASSBOOK_PAYMENTS_HISTORY =
            Pattern.compile("(?i)\\bpassbook\\s+payments\\s+history\\b");

    /** The lines a document yielded when nothing was extracted from it. */
    static boolean isPaytmPaymentHistory(List<UnparseableRow> recovered) {
        if (recovered == null || recovered.isEmpty()) return false;
        return containsBothHeadings(recovered.stream().map(PaymentAppHistoryDetector::textOf).toList());
    }

    /** The document's own text, as acquired, before any table was located -- so the answer does not
     *  depend on whether a table reader managed to stage rows from it. Each heading is one text run
     *  on the real document. */
    public static boolean containsBothHeadings(Iterable<String> texts) {
        boolean title = false;
        boolean table = false;
        for (String text : texts) {
            if (text == null) continue;
            title |= PAYTM_STATEMENT_TITLE.matcher(text).find();
            table |= PASSBOOK_PAYMENTS_HISTORY.matcher(text).find();
            if (title && table) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // UPI app transaction history (added 2026-10-09)
    //
    // A UPI app's history prints every payment as a short block: a line led by what happened to the
    // money ("Paid to <payee>", "Payment to ...", "Transfer to ...", "Received from <payer>",
    // "Cashback from ...", "Refund from ...", "Mobile recharged ..."), then reference lines, then a
    // line naming the user's bank account the money left or reached ("Paid by XXXX1234",
    // "Credited to XXXX1234"). There is no balance column, because no single account's balance is
    // being kept. Its rows span every bank account linked to the app, which is why it is refused for
    // the same reason as the Paytm history above.
    //
    // The rule is structural, not a brand name: the document has to carry that row grammar on both
    // halves -- at least MIN_UPI_ROWS lines led by a payment verb AND at least MIN_UPI_ROWS lines led
    // by a funding-account phrase -- and print no balance column heading anywhere. A bank statement
    // keeps a running balance and words its narrations differently.
    //
    // Measured on 2026-10-09 with the real text runs PdfTextExtractor produces: one real UPI app
    // history carried hundreds of lines of each half and no balance heading, and every one of its
    // rows opened with one of the seven payment phrases above. Across the 34 PDFs in the real corpus
    // (33 savings-account and credit-card statements, plus the Paytm history): no document had a
    // single funding line; at most one payment line (two card statements, each once); and every
    // statement with a text layer except one credit card printed a balance heading. Three is the
    // threshold, so a short history of a few payments is still recognised while staying clear of
    // every real statement measured.
    //
    // A history of one or two payments (a narrow date range) falls under that threshold, and staged
    // as rows it would import with garbled descriptions -- measured on a synthetic two-payment
    // history. For that case alone, one line of each half is enough when the document also prints
    // the app's own statement address: the real history printed it on every page, and no PDF in the
    // corpus prints it at all. A bare mention of the app is not enough; bank narrations name it
    // (several corpus statements do), which is why the rule wants the web address.
    // ---------------------------------------------------------------------------------------------

    /** Lines needed of EACH half of the row grammar. */
    static final int MIN_UPI_ROWS = 3;

    private static final Pattern APP_STATEMENT_ADDRESS = Pattern.compile("(?i)\\bphonepe\\.com\\b");

    // Anchored to the start of a text run: in a bank statement these words can occur inside a
    // narration, but a run that BEGINS with them is a row in this grammar.
    private static final Pattern PAYMENT_VERB_LINE = Pattern.compile(
            "(?i)^\\s*(paid\\s+to|payment\\s+to|transfer\\s+to|received\\s+from|cashback\\s+from|refund\\s+from"
                    + "|mobile\\s+recharged)\\b");
    private static final Pattern FUNDING_ACCOUNT_LINE = Pattern.compile(
            "(?i)^\\s*(paid\\s+by|credited\\s+to|debited\\s+from)\\b");
    // A column or summary heading for a balance, alone in its run: "Balance", "Closing Balance",
    // "Balance (INR)", "Avl Bal.". Every real statement in the corpus with a text layer prints at
    // least one -- except one credit card, which is why this is a guard and not the rule itself.
    private static final Pattern BALANCE_HEADING = Pattern.compile(
            "(?i)^\\s*(opening|closing|available|avl|running|previous|total|ledger|book)?\\s*bal(ance|\\.)?\\b[^0-9]{0,15}$");

    /** The document's own text runs carry a UPI app's row grammar -- three lines of each half, or
     *  one of each beside the app's statement address -- and no balance heading. */
    static boolean hasUpiAppRowGrammar(List<String> texts) {
        int paymentLines = 0;
        int fundingLines = 0;
        boolean appAddress = false;
        for (String text : texts) {
            if (text == null) continue;
            if (BALANCE_HEADING.matcher(text).find()) return false;
            if (PAYMENT_VERB_LINE.matcher(text).find()) paymentLines++;
            else if (FUNDING_ACCOUNT_LINE.matcher(text).find()) fundingLines++;
            appAddress |= APP_STATEMENT_ADDRESS.matcher(text).find();
        }
        if (paymentLines >= MIN_UPI_ROWS && fundingLines >= MIN_UPI_ROWS) return true;
        return appAddress && paymentLines >= 1 && fundingLines >= 1;
    }

    private static String textOf(UnparseableRow row) {
        if (row == null || row.raw() == null) return null;
        Map<String, String> raw = row.raw();
        // A PDF's recovered line arrives as one "text" cell; a CSV's as several named cells. Every
        // cell is read, so the headings are found whichever shape the line came in.
        return String.join(" ", raw.values().stream().filter(v -> v != null).toList());
    }
}
