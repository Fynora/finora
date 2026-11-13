package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.DocumentContext;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The newer HSBC savings layout ends its ledger with a "Transaction Turnover" line and a
 * "Transaction Count" line under CLOSING BALANCE, then the bank's address footer. None of it has a
 * date, so all of it was read as one line of narration waiting for a transaction that never came,
 * and reached the review screen as an unreadable line. The pair is the table's totals: kept as
 * auxiliary text, where the zero-activity check reads the count, and the footer under it is
 * suppressed. Synthetic values.
 */
class LedgerSummaryPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(suggestion);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()),
                com.finora.imports.TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private static String flat(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }

    private static PdfTableLocator.LocatedDocument locate(byte[] pdf, DocumentContext ctx) throws Exception {
        return new PdfTableLocator().locateAll(new PdfTextExtractor().extract(pdf), ctx);
    }

    @Test
    void theLedgerSummaryAndTheFooterUnderItAreNotReadAsLines() throws Exception {
        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "statement.pdf",
                PdfFixtureBuilder.buildRepeatedHeaderNoteWithLedgerSummarySample());

        assertThat(response.unparseableRows()).isEmpty();
        assertThat(response.rows()).extracting(StagedRow::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("50000.00"), new BigDecimal("1000.00"), new BigDecimal("500.00"));
        assertThat(flat(response.rows().get(2).description())).isEqualTo("UPI100000000005 100000000006 SAMPLE PAYEE TWO");
        assertThat(response.verification().findings())
                .filteredOn(f -> List.of("BALANCE_CHAIN", "STATEMENT_TOTALS", "ROW_ACCOUNTING").contains(f.rule()))
                .extracting(f -> f.rule() + "=" + f.outcome())
                .containsExactlyInAnyOrder("BALANCE_CHAIN=VERIFIED", "STATEMENT_TOTALS=VERIFIED", "ROW_ACCOUNTING=VERIFIED");
    }

    @Test
    void theSummaryLinesAreKeptAsAuxiliaryTextAndNothingAfterThem() throws Exception {
        DocumentContext ctx = new DocumentContext("PDF", "statement.pdf");
        var doc = locate(PdfFixtureBuilder.buildRepeatedHeaderNoteWithLedgerSummarySample(), ctx);

        assertThat(doc.sections()).hasSize(1);
        List<String> auxiliary = doc.sections().get(0).auxiliaryText().stream().map(LedgerSummaryPdfPreviewGeneratorTest::flat).toList();
        // The pair, last: the footer printed under it on the last page is not kept anywhere.
        assertThat(auxiliary.subList(auxiliary.size() - 2, auxiliary.size()))
                .containsExactly("Transaction Turnover 50,000.00 1,500.00", "Transaction Count 1 2");
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).contains("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aTurnoverLineWithoutItsCountLineIsNotReadAsTheLedgersSummary() throws Exception {
        DocumentContext ctx = new DocumentContext("PDF", "statement.pdf");
        var doc = locate(PdfFixtureBuilder.buildRepeatedHeaderNoteWithTurnoverLineOnlySample(), ctx);

        assertThat(doc.sections()).flatExtracting(PdfTableLocator.LocatedSection::auxiliaryText)
                .noneMatch(line -> line.contains("Transaction Turnover"));
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    private static List<PositionedText> ledgerEndingInATurnoverLine() {
        return new java.util.ArrayList<>(List.of(
                new PositionedText("Date", 40f, 100f, 0),
                new PositionedText("Transaction Details", 120f, 100f, 0),
                new PositionedText("Deposits", 330f, 100f, 0),
                new PositionedText("Withdrawals", 410f, 100f, 0),
                new PositionedText("Balance", 490f, 100f, 0),
                new PositionedText("30Oct2026", 40f, 115f, 0),
                new PositionedText("SAMPLE PAYEE ONE", 120f, 115f, 0),
                new PositionedText("1,000.00", 410f, 115f, 0),
                new PositionedText("7,000.00", 490f, 115f, 0),
                new PositionedText("Transaction Turnover", 120f, 700f, 0),
                new PositionedText("0.00", 330f, 700f, 0),
                new PositionedText("1,000.00", 410f, 700f, 0)));
    }

    @Test
    void aTurnoverLineThatEndsTheDocumentIsNotTheSummary() throws Exception {
        DocumentContext ctx = new DocumentContext("PDF", "statement.pdf");
        new PdfTableLocator().locateAll(ledgerEndingInATurnoverLine(), ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability()).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aCountLineAtTheTopOfTheNextPageDoesNotCompleteTheSummary() throws Exception {
        // Built from runs, so the turnover line can sit at the foot of page 1 and a count line open
        // page 2: the pair must be printed together, one line under the other.
        List<PositionedText> runs = ledgerEndingInATurnoverLine();
        runs.addAll(List.of(
                new PositionedText("Transaction Count", 120f, 40f, 1),
                new PositionedText("0", 330f, 40f, 1),
                new PositionedText("1", 410f, 40f, 1)));
        DocumentContext ctx = new DocumentContext("PDF", "statement.pdf");
        new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability()).doesNotContain("TABLE_TOTALS_ROW_DIVERTED");
    }

    @Test
    void aStatementThatPrintsNoActivityIsStillRecognisedAsOne() throws Exception {
        var result = realGenerator().generateSectionsWithContext(UUID.randomUUID(), "statement.pdf",
                PdfFixtureBuilder.buildExplicitZeroTransactionCountAsPrintedSample());

        assertThat(result.documentContext().explicitZeroActivityDeclared()).isTrue();
        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).rows()).isEmpty();
        assertThat(result.sections().get(0).unparseableRows()).isEmpty();
    }
}
