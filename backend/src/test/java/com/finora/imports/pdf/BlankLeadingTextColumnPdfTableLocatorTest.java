package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.DocumentContext;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Axis Bank savings layout: "Tran Date | Chq No | Particulars | Debit | Credit | Balance | Init. Br",
 * with the Particulars label centred far to the right of where its left-aligned narration starts,
 * Chq No blank on almost every row, each narration's first line printed above its date row, the
 * "Init." heading wrapped onto a second line ("Br"), and a "TRANSACTION TOTAL" line under the last
 * transaction.
 *
 * <p>Modeled on a real statement held for review on 2026-11-11. Every value here is synthetic, per the
 * Synthetic Fixture Policy; the column positions keep that layout's geometry, the structural property
 * these tests depend on. Four faults stacked on it: "Tran Date" was no date
 * column, so every row was rejected; every narration was bucketed into Chq No (BLANK_LEADING_TEXT_COLUMN),
 * so every description was empty; "Br" became the first row, so the first transaction's opening
 * narration line was glued onto "OPENING BALANCE" (HEADER_LABEL_WRAP_SUPPRESSED); and the totals
 * line merged into the last transaction, turning its debit into a credit.
 */
class BlankLeadingTextColumnPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float y, float width) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** A right-aligned amount ending at {@code right}. */
    private static PositionedText amount(String text, float right, float y) {
        float width = 4.5f * text.length();
        return run(text, right - width, y, width);
    }

    private static final float DEBIT_RIGHT = 380f;
    private static final float CREDIT_RIGHT = 442f;
    private static final float BALANCE_RIGHT = 530f;

    private List<PositionedText> documentRuns() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Tran Date", 40f, 100f, 40f),
                run("Chq No", 92f, 100f, 30f),
                run("Particulars", 203f, 100f, 43f),
                run("Debit", 339f, 100f, 21f),
                run("Credit", 400f, 100f, 25f),
                run("Balance", 473f, 100f, 30f),
                run("Init.", 538f, 100f, 16f),
                run("Br", 541f, 109f, 10f),

                run("OPENING BALANCE", 132f, 129f, 88f),
                amount("10000.00", BALANCE_RIGHT, 129f)));

        // 1: a two-line narration, its first line above the date row.
        runs.addAll(List.of(
                run("UPI/P2M/100000000001/SAMPLE SHOP", 132f, 142f, 150f),
                run("01-04-2025", 38f, 151f, 42f),
                run("PAYMENT/UPI/SAMPLE BANK", 132f, 151f, 120f),
                amount("244.00", DEBIT_RIGHT, 151f),
                amount("9756.00", BALANCE_RIGHT, 151f),
                run("1234", 537f, 151f, 18f)));
        // 2: a short narration that reaches no label -- left-aligned with the long ones.
        runs.addAll(List.of(
                run("UPI/P2A/100000000002/SAMPLE PERSON", 132f, 164f, 160f),
                run("02-04-2025", 38f, 173f, 42f),
                run("XYZ", 132f, 173f, 18f),
                amount("300.00", CREDIT_RIGHT, 173f),
                amount("10056.00", BALANCE_RIGHT, 173f),
                run("1234", 537f, 173f, 18f)));
        // 3: a cheque, its number under Chq No.
        runs.addAll(List.of(
                run("03-04-2025", 38f, 195f, 42f),
                run("000123", 92f, 195f, 25f),
                run("CHEQUE PAID TO SAMPLE PAYEE", 132f, 195f, 130f),
                amount("1000.00", DEBIT_RIGHT, 195f),
                amount("9056.00", BALANCE_RIGHT, 195f),
                run("1234", 537f, 195f, 18f)));
        // 4: a narration line shaped like a reference (no spaces, digits), starting where narration starts.
        runs.addAll(List.of(
                run("NEFT/100000000003/SAMPLECO", 132f, 208f, 120f),
                run("04-04-2025", 38f, 217f, 42f),
                run("SALARY SAMPLE EMPLOYER", 132f, 217f, 110f),
                amount("5000.00", CREDIT_RIGHT, 217f),
                amount("14056.00", BALANCE_RIGHT, 217f),
                run("1234", 537f, 217f, 18f)));
        // 5: the last transaction, a debit, then the totals and the closing balance.
        runs.addAll(List.of(
                run("UPI/P2M/100000000004/SAMPLE CAFE", 132f, 230f, 150f),
                run("05-04-2025", 38f, 239f, 42f),
                run("PAYMENT/UPI/SAMPLE BANK", 132f, 239f, 120f),
                amount("20.00", DEBIT_RIGHT, 239f),
                amount("14036.00", BALANCE_RIGHT, 239f),
                run("1234", 537f, 239f, 18f),

                run("TRANSACTION TOTAL", 132f, 263f, 100f),
                amount("1264.00", DEBIT_RIGHT, 263f),
                amount("5300.00", CREDIT_RIGHT, 263f),
                run("CLOSING BALANCE", 132f, 283f, 88f),
                amount("14036.00", BALANCE_RIGHT, 283f)));
        return runs;
    }

    private List<Map<String, String>> transactions(DocumentContext ctx) {
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(documentRuns(), ctx);
        assertThat(doc.sections()).hasSize(1);
        // The transactions: the rows with a debit or a credit. The opening and closing balance lines
        // carry a balance alone.
        return doc.sections().get(0).rows().stream()
                .filter(r -> r.get("Debit") != null || r.get("Credit") != null).toList();
    }

    @Test
    void everyNarrationLandsInParticulars_notInTheBlankChqNoColumn() {
        List<Map<String, String>> rows = transactions(new DocumentContext("PDF", "BlankLeadingTextColumnTest"));

        assertThat(rows).extracting(r -> r.get("Particulars")).containsExactly(
                "UPI/P2M/100000000001/SAMPLE SHOP PAYMENT/UPI/SAMPLE BANK",
                "UPI/P2A/100000000002/SAMPLE PERSON XYZ",
                "CHEQUE PAID TO SAMPLE PAYEE",
                "NEFT/100000000003/SAMPLECO SALARY SAMPLE EMPLOYER",
                "UPI/P2M/100000000004/SAMPLE CAFE PAYMENT/UPI/SAMPLE BANK");
    }

    @Test
    void aChequeNumberPrintedUnderChqNoStaysThere() {
        List<Map<String, String>> rows = transactions(new DocumentContext("PDF", "BlankLeadingTextColumnTest"));

        assertThat(rows).extracting(r -> r.get("Chq No")).containsExactly(null, null, "000123", null, null);
    }

    @Test
    void theHeadingsSecondLineIsNotARow_andTheOpeningBalanceKeepsNoNarration() {
        DocumentContext ctx = new DocumentContext("PDF", "BlankLeadingTextColumnTest");
        List<Map<String, String>> all = new PdfTableLocator().locateAll(documentRuns(), ctx).sections().get(0).rows();

        assertThat(all).noneMatch(r -> r.containsValue("Br"));
        assertThat(all.get(0).get("Particulars")).isEqualTo("OPENING BALANCE");
        assertThat(ctx.capabilities()).extracting("capability").contains("HEADER_LABEL_WRAP_SUPPRESSED");
    }

    @Test
    void theTransactionTotalLineIsNotMergedIntoTheLastTransaction() {
        DocumentContext ctx = new DocumentContext("PDF", "BlankLeadingTextColumnTest");
        List<Map<String, String>> rows = transactions(ctx);

        Map<String, String> last = rows.get(rows.size() - 1);
        assertThat(last.get("Debit").trim()).isEqualTo("20.00");
        assertThat(last.get("Credit")).isNull();
        assertThat(ctx.capabilities()).extracting("capability").contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void endToEnd_everyTransactionStagesWithItsDescription_andTheAmountsReconcile() {
        PdfPreviewGenerator.PdfGenerationResult result = generator().generateSectionsFromAcquired(
                UUID.randomUUID(), "statement.pdf", AcquiredDocument.of(documentRuns()));
        StagedAccountSection section = result.sections().get(0);
        List<StagedRow> rows = section.rows();

        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(r -> assertThat(r.description()).isNotBlank());
        assertThat(rows).extracting(StagedRow::type)
                .containsExactly("EXPENSE", "INCOME", "EXPENSE", "INCOME", "EXPENSE");
        assertThat(rows).extracting(r -> r.amount().toPlainString())
                .containsExactly("244.00", "300.00", "1000.00", "5000.00", "20.00");
        BigDecimal net = rows.stream()
                .map(r -> "INCOME".equals(r.type()) ? r.amount() : r.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(new BigDecimal("10000.00").add(net)).isEqualByComparingTo("14036.00");
        assertThat(section.verification().findings()).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("BALANCE_CHAIN");
            assertThat(f.outcome()).isEqualTo("VERIFIED");
        });
    }

    /** A left-aligned "Description" heading with a short first narration line printed right under it:
     *  narration, not a wrapped heading word, however neatly it fits inside the label. */
    @Test
    void aShortFirstNarrationLineUnderADescriptionHeadingIsKept() {
        List<PositionedText> runs = List.of(
                run("Date", 40f, 100f, 20f), run("Description", 100f, 100f, 50f),
                run("Debit", 339f, 100f, 21f), run("Credit", 400f, 100f, 25f), run("Balance", 473f, 100f, 30f),

                run("UPI", 100f, 109f, 15f),
                run("01-04-2025", 38f, 118f, 42f), run("PAYMENT TO SAMPLE SHOP", 100f, 118f, 110f),
                amount("244.00", DEBIT_RIGHT, 118f), amount("9756.00", BALANCE_RIGHT, 118f),

                run("02-04-2025", 38f, 131f, 42f), run("SALARY SAMPLE EMPLOYER", 100f, 131f, 110f),
                amount("300.00", CREDIT_RIGHT, 131f), amount("10056.00", BALANCE_RIGHT, 131f));
        DocumentContext ctx = new DocumentContext("PDF", "BlankLeadingTextColumnTest");

        List<Map<String, String>> rows = new PdfTableLocator().locateAll(runs, ctx).sections().get(0).rows();

        assertThat(rows.get(0).get("Description")).isEqualTo("UPI PAYMENT TO SAMPLE SHOP");
        assertThat(ctx.capabilities()).extracting("capability").doesNotContain("HEADER_LABEL_WRAP_SUPPRESSED");
    }

    /** A narration column first and a reference column after it, the narration printed right of its
     *  own short heading and long enough to run into the reference heading. A narration is never
     *  moved out of its description column, so it is not measured as the reference column's data. */
    @Test
    void aNarrationRunningIntoTheNextHeadingStaysInItsDescriptionColumn() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 40f, 100f, 20f), run("Narration", 100f, 100f, 40f), run("Ref No", 300f, 100f, 28f),
                run("Debit", 360f, 100f, 21f), run("Credit", 410f, 100f, 25f), run("Balance", 473f, 100f, 30f)));
        for (int i = 0; i < 4; i++) {
            float y = 120f + 13f * i;
            runs.add(run("0" + (i + 1) + "-04-2025", 38f, y, 42f));
            runs.add(run("PAYMENT TO A SAMPLE SHOP WITH A LONG NAME", 145f, y, 175f));
            if (i == 3) runs.add(run("REF000004", 330f, y, 40f)); // only the last row prints a reference
            runs.add(amount("10.00", 395f, y));
            runs.add(amount((1000 - 10 * (i + 1)) + ".00", BALANCE_RIGHT, y));
        }

        List<Map<String, String>> rows = new PdfTableLocator().locateAll(runs, null).sections().get(0).rows();

        assertThat(rows).hasSize(4).allSatisfy(r ->
                assertThat(r.get("Narration")).isEqualTo("PAYMENT TO A SAMPLE SHOP WITH A LONG NAME"));
        assertThat(rows).extracting(r -> r.get("Ref No")).containsExactly(null, null, null, "REF000004");
    }

    private static PdfPreviewGenerator generator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        CategorizationService.Suggestion none =
                new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any())).thenReturn(none);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(none);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(none);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService, duplicateDetector, TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                TestRuleEngines.empty());
    }
}
