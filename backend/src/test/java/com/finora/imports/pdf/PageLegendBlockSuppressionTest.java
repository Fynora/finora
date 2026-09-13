package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAGE_LEGEND_BLOCK_SUPPRESSED. Verified against a real SBI credit-card statement: a legal/legend
 * block ("Transactions highlighted in grey color...", the "C=Credit ; D=Debit..." abbreviation
 * key, an "Important Messages" heading, then open-ended late-payment-charges prose) prints at the
 * bottom of EVERY page, not once at the true end of the table. With no recognized boundary marker
 * for it, the ordinary trailing-continuation merge glued the whole block onto the last real
 * transaction above the page break -- see pageLegendBlockActive's own doc comment in
 * PdfTableLocator.
 */
class PageLegendBlockSuppressionTest {

    private static PositionedText run(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    @Test
    void legendBlockAtAPageBreak_doesNotPolluteTheLastTransactionAboveIt_andRealRowsResumeOnTheNextPage() {
        List<PositionedText> positioned = new ArrayList<>();
        // Page 0: header, one real transaction, then the page-end legend block.
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-VMPL DEL 24", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        positioned.add(run("Transactions highlighted in grey color, if any, do not form part of "
                + "Purchases & Other Debits", 20f, 400f, 140f, 0));
        positioned.add(run("C=Credit ; D=Debit; EN=Encash; FP=Flexipay", 20f, 350f, 150f, 0));
        positioned.add(run("Important Messages", 250f, 90f, 160f, 0));
        positioned.add(run("W.e.f. 1st May 2026, Late Payment Charges will be revised", 20f, 400f, 170f, 0));
        // Page 1: the header repeats, then real transactions resume.
        positioned.add(run("Date", 40f, 30f, 50f, 1));
        positioned.add(run("Description", 100f, 80f, 50f, 1));
        positioned.add(run("Amount", 300f, 45f, 50f, 1));
        positioned.add(run("12 Jul 26", 40f, 45f, 70f, 1));
        positioned.add(run("UPI-ZOMATO", 100f, 80f, 70f, 1));
        positioned.add(run("25.00", 300f, 40f, 70f, 1));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .as("the legend block must not be glued onto the last transaction above the page break")
                .containsEntry("Description", "UPI-VMPL DEL 24")
                .containsEntry("Amount", "390.00");
        assertThat(rows.get(1))
                .as("a real transaction on the next page must still be recovered, not discarded with the legend")
                .containsEntry("Description", "UPI-ZOMATO")
                .containsEntry("Amount", "25.00");

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void legendBlockWithNoRepeatedHeaderOnTheNextPage_stillResumesOnceARealTransactionRowIsSeen() {
        // A real HDFC savings statement (24 pages) prints its header exactly once, on page 1, and
        // never again -- every later page's transactions resume directly with no header row at all.
        // pageLegendBlockActive's only resume signal used to be a newly recognized header row, so
        // once this page-1 footer set the flag, there was no "next header" ever again to clear it --
        // 231 of 243 real transactions were silently dropped for the rest of the document.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-VMPL DEL 24", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        positioned.add(run("Closing balance includes funds earmarked for hold and uncleared funds",
                20f, 400f, 140f, 0));
        positioned.add(run("Address correctness is the customer's own responsibility", 20f, 400f, 150f, 0));
        // Page 1: NO repeated header -- a real transaction resumes directly.
        positioned.add(run("12 Jul 26", 40f, 45f, 50f, 1));
        positioned.add(run("UPI-ZOMATO", 100f, 80f, 50f, 1));
        positioned.add(run("25.00", 300f, 40f, 50f, 1));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the real transaction on page 1 must be recovered even with no repeated header to "
                        + "reset the legend-block suppression")
                .hasSize(2);
        assertThat(rows.get(1))
                .containsEntry("Description", "UPI-ZOMATO")
                .containsEntry("Amount", "25.00");
    }

    @Test
    void chequePayableFooter_onANonFinalPage_doesNotPolluteTheTransactionAboveIt_andRealRowsResumeLater() {
        // Real bug, reported directly by a user: a real Axis Bank credit-card statement's own
        // 2-page ledger (108 transactions total, 28 on page 0) ends page 0 with "Your cheque should
        // be payable to..." immediately followed by a "Dear Customer, pay your ... bill ... ECS..."
        // sentence and a GST-registration line -- right after that page's own last transaction, with
        // 80 more real transactions still to come on page 1 before the document's true end
        // ("**** End of Statement ****", two pages later in the real document).
        //
        // This sentence used to be its own permanently-closing trigger, gated on "only fires on the
        // document's own actual last page" -- which this document's real shape (page 0 of many)
        // fails, so the guard silently declined to fire and the whole footer block was swept into
        // the preceding transaction's own description via the ordinary trailing-continuation merge.
        // Confirmed directly against the real document before this fix: the merged description read
        // "<real narration> Your cheque should be payable to Axis Bank Card No....". Now folded into
        // PAGE_LEGEND_BLOCK_START instead, which resumes on the next transaction-shaped row
        // regardless of which page it lands on -- no page-position guess needed.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("29 Jun 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-RESTAURANT ONE", 100f, 80f, 120f, 0));
        positioned.add(run("35.00", 300f, 40f, 120f, 0));
        positioned.add(run("Your cheque should be payable to Axis Bank Card No.XXXXXXXXXXXX1234. "
                + "Please write your NAME & TELEPHONE No. on the reverse of the cheque.",
                20f, 400f, 140f, 0));
        positioned.add(run("Dear Customer, pay your Axis Bank Credit Card bill from any bank account "
                + "by registering for ECS at any Axis Bank branch. Visit axis.bank.in to download the form.",
                20f, 400f, 150f, 0));
        positioned.add(run("Axis Bank Maharashtra GST registration no.: XXXXXXXXXXXXXXX",
                20f, 400f, 160f, 0));
        // Page 1: NOT the document's last page either (a genuine multi-page ledger), more real
        // transactions resume directly with no repeated header.
        positioned.add(run("10 Jul 26", 40f, 45f, 50f, 1));
        positioned.add(run("UPI-DEPT STORE ONE", 100f, 80f, 50f, 1));
        positioned.add(run("249.00", 300f, 40f, 50f, 1));
        // Page 2: the document's own true end.
        positioned.add(run("**** End of Statement ****", 200f, 200f, 400f, 2));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the cheque-payable footer must not merge into the transaction directly above it, "
                        + "and the real transaction on the next page must still be recovered")
                .hasSize(2);
        assertThat(rows.get(0))
                .as("this is the exact real-world corruption reported: the footer text landing inside "
                        + "a real transaction's own description")
                .containsEntry("Description", "UPI-RESTAURANT ONE")
                .containsEntry("Amount", "35.00");
        assertThat(rows.get(1))
                .containsEntry("Description", "UPI-DEPT STORE ONE")
                .containsEntry("Amount", "249.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void chequePayableFooter_atTheDocumentsActualEnd_stillClosesCleanlyWithNoCorruption() {
        // The one real Axis document this sentence is evidenced from prints it only once, but not
        // every Axis statement is guaranteed to have more transactions after it -- a shorter
        // single-page statement could plausibly end here for real. Must still work with no special
        // page-position check: with no more transaction-shaped rows after the footer, the
        // suppression simply never resets, which is exactly the desired outcome.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-RESTAURANT ONE", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        positioned.add(run("Your cheque should be payable to Axis Bank Card No.XXXXXXXXXXXX1234",
                20f, 400f, 140f, 0));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .as("the true-end case must also leave the last real transaction uncorrupted")
                .containsEntry("Description", "UPI-RESTAURANT ONE")
                .containsEntry("Amount", "390.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void credPointsFooter_onANonFinalPage_doesNotPolluteTheTransactionAboveIt_andRealRowsResumeLater() {
        // Real bug, found by DescriptionCorruptionValidator (not by hand) on a real IndusInd Bank
        // ("CRED IndusInd Bank RuPay") credit-card statement: a "Purchases & Cash Transactions"
        // section's last real transaction is followed by a "CRED Points Transferred* NOTE: CRED
        // Points earned via spending..." rewards footnote -- right after that section's own last
        // transaction, with 2 more real transactions still to come in a fresh "ACCOUNT SUMMARY"
        // section on page 2, before the document's true end (page 3's terms/fees text). Confirmed
        // directly against the real document before this fix: the merged description read "<real
        // narration> NOTE: CRED Points earned via spending on your CRED IndusInd Bank RuPay Credit
        // Card...". Same failure shape and same fix as the Axis case above -- a different bank, a
        // different sentence, found by a check built to generalize past the one bank it was
        // evidenced from.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("12 Aug 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-RESTAURANT ONE", 100f, 80f, 120f, 0));
        positioned.add(run("70.00", 300f, 40f, 120f, 0));
        positioned.add(run("CRED Points Transferred* NOTE: CRED Points earned via spending on your "
                + "CRED IndusInd Bank RuPay Credit Card during the current billing cycle are "
                + "mentioned against each transactions.",
                20f, 400f, 140f, 0));
        positioned.add(run("It may take up to 2-3 business days for the CRED Points to reflect in "
                + "your CRED Account.", 20f, 400f, 150f, 0));
        // Page 1: NOT the document's last page either (a genuine multi-page ledger), more real
        // transactions resume directly with no repeated header.
        positioned.add(run("12 Aug 26", 40f, 45f, 50f, 1));
        positioned.add(run("UPI-DEPT STORE ONE", 100f, 80f, 50f, 1));
        positioned.add(run("136.00", 300f, 40f, 50f, 1));
        // Page 2: the document's own true end.
        positioned.add(run("**** End of Statement ****", 200f, 200f, 400f, 2));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the CRED Points footnote must not merge into the transaction directly above "
                        + "it, and the real transaction on the next page must still be recovered")
                .hasSize(2);
        assertThat(rows.get(0))
                .as("this is the exact real-world corruption found: the footnote text landing "
                        + "inside a real transaction's own description")
                .containsEntry("Description", "UPI-RESTAURANT ONE")
                .containsEntry("Amount", "70.00");
        assertThat(rows.get(1))
                .containsEntry("Description", "UPI-DEPT STORE ONE")
                .containsEntry("Amount", "136.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void bareColumnTotalRow_betweenTwoSubTablesOnTheSamePage_doesNotPolluteTheTransactionAboveIt_andRealRowsResumeAfterTheBanner() {
        // Real bug found while verifying the CRED-points fix above against the real IndusInd
        // document (that fix's own commit message flagged this as a separate, unrelated follow-up):
        // the document prints multiple sub-tables under one "ACCOUNT SUMMARY" heading. A sub-table
        // closes with a bare "Total" column-recap row (CRED-points column, then amount column), and
        // the NEXT sub-table opens immediately with its own identity banner ("<Sub-table name> for
        // <cardholder> (Card No. ...)") repeating the cardholder/masked-card-number text the FIRST
        // sub-table's own opening banner already printed -- before that next sub-table's own real
        // transactions begin. Confirmed via a direct dump of PdfTableLocator's own physical rows
        // (lineOf) against the real document: with nothing recognizing the "Total" row as boilerplate,
        // the ordinary trailing-continuation merge glued both the "Total" row AND the banner onto the
        // last real transaction of the PRECEDING sub-table.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-RESTAURANT ONE", 100f, 80f, 120f, 0));
        positioned.add(run("35.00", 300f, 40f, 120f, 0));
        positioned.add(run("Total 0 35.00", 40f, 90f, 130f, 0));
        positioned.add(run("Purchases for MR TEST CARDHOLDER (Card No. 1234XXXXXXXX5678)",
                20f, 400f, 140f, 0));
        positioned.add(run("12 Jul 26", 40f, 45f, 150f, 0));
        positioned.add(run("UPI-DEPT STORE ONE", 100f, 80f, 150f, 0));
        positioned.add(run("249.00", 300f, 40f, 150f, 0));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the Total row and the next sub-table's banner must not merge into the "
                        + "transaction above them, and the next sub-table's own transaction must "
                        + "still be recovered")
                .hasSize(2);
        assertThat(rows.get(0))
                .as("this is the exact real-world corruption found: the Total row and the "
                        + "following banner landing inside a real transaction's own description")
                .containsEntry("Description", "UPI-RESTAURANT ONE")
                .containsEntry("Amount", "35.00");
        assertThat(rows.get(1))
                .containsEntry("Description", "UPI-DEPT STORE ONE")
                .containsEntry("Amount", "249.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void bareColumnTotalRow_atTheDocumentsActualEnd_doesNotPolluteTheLastTransaction() {
        // Same real document, second occurrence (also flagged by the CRED-points fix's commit as a
        // separate follow-up): the document's OWN closing "Total" recap is immediately followed by a
        // page number and the next page's payments/terms section headers, with no more real
        // transactions after it. Same trigger, no resume signal ever arrives -- must still leave the
        // last real transaction clean, the same "never resets" safety this pattern's other entries
        // already rely on for their own true-end case.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("UPI-RESTAURANT ONE", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        positioned.add(run("Total 0 390.00", 40f, 90f, 130f, 0));
        positioned.add(run("2", 300f, 15f, 140f, 0));
        positioned.add(run("HOW TO MAKE PAYMENTS TO CHECK AVAILABLE REWARD POINTS",
                20f, 400f, 150f, 0));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .as("the true-end case must also leave the last real transaction uncorrupted")
                .containsEntry("Description", "UPI-RESTAURANT ONE")
                .containsEntry("Amount", "390.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }
}
