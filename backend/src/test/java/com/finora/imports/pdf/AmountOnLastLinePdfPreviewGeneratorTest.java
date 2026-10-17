package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A ledger that prints a transaction's date on its first line and its figures on its last --
 * modeled on a real scanned HSBC savings statement (the synthetic sample takes the same layout
 * fingerprint). Before AMOUNTLESS_ANCHOR_COMPLETED, a five-line bank transfer pushed its own figures
 * past the trailing cap: the deposit staged under the next day's date, every description after it
 * carried the next transaction's reference lines, a date OCR read as "O5SEP2026" merged two
 * transactions, and a transaction whose amount was not read vanished without a trace. This pins
 * each of those, value by value -- and, beside it, the layout the new rule must leave alone.
 */
class AmountOnLastLinePdfPreviewGeneratorTest {

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

    private StagingResponse generate(byte[] bytes) throws Exception {
        return realGenerator().generate(UUID.randomUUID(), "statement.pdf", bytes);
    }

    private static String flat(String description) {
        return description == null ? null : description.replace('\n', ' ');
    }

    @Test
    void aFiveLineTransferKeepsItsOwnDateFiguresAndNarration() throws Exception {
        List<StagedRow> rows = generate(PdfFixtureBuilder.buildAmountOnLastLineSample()).rows();

        StagedRow transfer = rows.get(0);
        assertThat(transfer.date()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(transfer.type()).isEqualTo("INCOME");
        assertThat(transfer.amount()).isEqualByComparingTo("50000.00");
        assertThat(transfer.balanceAfter()).isEqualByComparingTo("58000.00");
        // Every narration line, the one printed just above the figures included.
        assertThat(flat(transfer.description())).isEqualTo(
                "NEFTREF100000000001 NEFT FROM SAMPLE PAYER PVT LTD SAMPLE BANK NA REF100000000002");
    }

    @Test
    void sameDayTransactionsKeepTheirOwnReferenceLines() throws Exception {
        List<StagedRow> rows = generate(PdfFixtureBuilder.buildAmountOnLastLineSample()).rows();

        assertThat(rows.get(1).date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(rows.get(1).amount()).isEqualByComparingTo("1000.00");
        assertThat(flat(rows.get(1).description())).isEqualTo("UPI100000000003 100000000004 SAMPLE PAYEE ONE");
        // The second same-day transaction prints no date and its reference lines sit under the first
        // one's figures: they are its own, not a tail of the first.
        assertThat(rows.get(2).date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(rows.get(2).amount()).isEqualByComparingTo("500.00");
        assertThat(rows.get(2).balanceAfter()).isEqualByComparingTo("56500.00");
        assertThat(flat(rows.get(2).description())).isEqualTo("UPI100000000005 100000000006 SAMPLE PAYEE TWO");
    }

    @Test
    void aDateOcrReadWithALetterOStillAnchorsItsTransaction() throws Exception {
        List<StagedRow> rows = generate(PdfFixtureBuilder.buildAmountOnLastLineSample()).rows();

        assertThat(rows).hasSize(4);
        StagedRow ecs = rows.get(3);
        assertThat(ecs.date()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(ecs.amount()).isEqualByComparingTo("2000.00");
        assertThat(flat(ecs.description())).isEqualTo("ECS-SAMPLE 100000000009 100000000010");
    }

    /** A transaction whose amount was not read is surfaced, never silently excluded as if it were
     *  an opening-balance line: its balance moved, and only a transaction moves a balance. */
    @Test
    void aTransactionWhoseAmountWasNotReadIsSurfacedNotDropped() throws Exception {
        StagingResponse response = generate(PdfFixtureBuilder.buildAmountOnLastLineSample());

        List<UnparseableRow> unread = response.unparseableRows().stream()
                .filter(u -> u.reason().contains("running balance changed")).toList();
        assertThat(unread).hasSize(1);
        assertThat(unread.get(0).raw().values()).anyMatch(v -> v.contains("SAMPLE PAYEE THREE"));
        // The statement's opening line repeats no earlier balance -- it is a genuine marker, not flagged.
        assertThat(response.unparseableRows()).noneMatch(u -> u.raw().values().stream()
                .anyMatch(v -> v.contains("BALANCE BROUGHT FORWARD")));
        assertThat(response.rows()).noneMatch(r -> r.balanceAfter() != null
                && r.balanceAfter().compareTo(new BigDecimal("56000.00")) == 0);
    }

    /** Figures in the middle of a block, more narration under them: the trailing branch already
     *  merges the figures, and the narration's tail must stay on its transaction. */
    @Test
    void onANewestFirstStatementOnlyTheAmountlessTransactionIsFlaggedNotItsBalanceMarkers() throws Exception {
        StagingResponse response = generate(PdfFixtureBuilder.buildNewestFirstBalanceMarkersSample());

        assertThat(response.rows()).extracting(StagedRow::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("200.00"), new BigDecimal("2000.00"),
                        new BigDecimal("1000.00"));
        // The closing balance (top edge), the brought-forward line (restates the row below it) and
        // the opening balance (bottom edge, under the oldest transaction) are markers, not unread
        // amounts. Only the row whose balance matches neither neighbour is.
        List<UnparseableRow> moved = response.unparseableRows().stream()
                .filter(u -> u.reason().startsWith("The running balance changed on this row"))
                .toList();
        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).raw().values()).anyMatch(v -> v.contains("SAMPLE PAYEE TWO"));
    }

    @Test
    void figuresInTheMiddleOfABlockKeepTheNarrationPrintedUnderThem() throws Exception {
        List<StagedRow> rows = generate(PdfFixtureBuilder.buildFiguresMidBlockSample()).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("120.00");
        assertThat(flat(rows.get(0).description())).contains("UPI/100000000001/DR/SAMPLE")
                .contains("PAYEE ONE/BAR/Payment f");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("1000.00");
        assertThat(flat(rows.get(1).description())).contains("PAYEE TWO/SBI/UPI");
    }
}
