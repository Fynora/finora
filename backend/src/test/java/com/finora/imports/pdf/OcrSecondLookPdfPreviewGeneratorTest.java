package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.acquisition.DocumentTextAcquirer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The second look at a scanned statement whose first reading skipped an amount. Modeled on a real
 * scanned HSBC statement: the first reading had no run at all where one amount is printed, and a
 * sparse-text second reading read it. The second reading is kept only when the document's own
 * figures prove it; otherwise the first stands and is held as before. Synthetic values throughout.
 */
class OcrSecondLookPdfPreviewGeneratorTest {

    /** A recogniser stand-in: the given first reading, and a second look on request, both marked
     *  as recognised text so the generator treats them as a scan. */
    private static final class FakeScan implements DocumentTextAcquirer {
        private final List<PositionedText> first;
        private final List<PositionedText> second;
        int secondLooks;

        FakeScan(byte[] firstPdf, byte[] secondPdf) throws IOException {
            this.first = asRecognised(new PdfTextExtractor().extract(firstPdf));
            this.second = secondPdf == null ? null : asRecognised(new PdfTextExtractor().extract(secondPdf));
        }

        @Override
        public AcquiredDocument acquire(byte[] fileBytes, String password) {
            return AcquiredDocument.of(first);
        }

        @Override
        public Optional<AcquiredDocument> secondLook(byte[] fileBytes, String password) {
            secondLooks++;
            return second == null ? Optional.empty() : Optional.of(AcquiredDocument.of(second));
        }

        @Override
        public boolean supports(byte[] fileBytes) {
            return true;
        }
    }

    private static List<PositionedText> asRecognised(List<PositionedText> runs) {
        return runs.stream().map(r -> new PositionedText(r.text(), r.x(), r.y(), r.pageIndex(), r.width(),
                r.height(), 0.95f, TextSource.OCR)).toList();
    }

    private static PdfPreviewGenerator generator(DocumentTextAcquirer acquirer) {
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
        return new PdfPreviewGenerator(acquirer, new PdfTableLocator(), new PdfMetadataExtractor(),
                transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    private static PdfPreviewGenerator.PdfGenerationResult read(DocumentTextAcquirer acquirer) throws IOException {
        return generator(acquirer).generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", new byte[0], null);
    }

    private static List<StagedRow> rows(PdfPreviewGenerator.PdfGenerationResult result) {
        return result.sections().get(0).rows();
    }

    @Test
    void aSecondReadingThatReadsTheSkippedAmountAndVerifiesIsKept() throws IOException {
        var scan = new FakeScan(PdfFixtureBuilder.buildAmountOnLastLineSample(),
                PdfFixtureBuilder.buildAmountOnLastLineSampleEveryAmountRead());

        var result = read(scan);

        assertThat(scan.secondLooks).isEqualTo(1);
        assertThat(result.documentContext().diagnostics())
                .doesNotContain(PdfPreviewGenerator.BALANCE_MOVED_WITHOUT_AN_AMOUNT);
        assertThat(rows(result)).anyMatch(r -> r.amount().compareTo(new BigDecimal("500.00")) == 0
                && r.balanceAfter() != null && r.balanceAfter().compareTo(new BigDecimal("56000.00")) == 0);
        assertThat(result.sections().get(0).unparseableRows())
                .noneMatch(u -> u.reason().contains("running balance changed"));
    }

    @Test
    void aSecondReadingThatStillMissesTheAmountLeavesTheFirstStanding() throws IOException {
        var scan = new FakeScan(PdfFixtureBuilder.buildAmountOnLastLineSample(),
                PdfFixtureBuilder.buildAmountOnLastLineSample());

        var result = read(scan);

        assertThat(scan.secondLooks).isEqualTo(1);
        assertThat(result.documentContext().diagnostics())
                .contains(PdfPreviewGenerator.BALANCE_MOVED_WITHOUT_AN_AMOUNT, "OCR_SECOND_LOOK_NOT_PROVEN");
        assertThat(result.sections().get(0).unparseableRows())
                .anyMatch(u -> u.reason().contains("running balance changed"));
    }

    @Test
    void aSecondReadingWhoseBalancesDoNotChainIsRefused() throws IOException {
        // Every amount read, but one read wrong: its chain cannot verify, so it proves nothing.
        var scan = new FakeScan(PdfFixtureBuilder.buildAmountOnLastLineSample(),
                PdfFixtureBuilder.buildAmountOnLastLineSampleEveryAmountReadOneAmountMisread());

        var result = read(scan);

        assertThat(result.documentContext().diagnostics()).contains("OCR_SECOND_LOOK_NOT_PROVEN");
        assertThat(result.sections().get(0).unparseableRows())
                .anyMatch(u -> u.reason().contains("running balance changed"));
    }

    @Test
    void noSecondLookWhenTheFirstReadingSkippedNothing() throws IOException {
        var scan = new FakeScan(PdfFixtureBuilder.buildAmountOnLastLineSampleEveryAmountRead(),
                PdfFixtureBuilder.buildAmountOnLastLineSampleEveryAmountRead());

        read(scan);

        assertThat(scan.secondLooks).isZero();
    }

    @Test
    void noSecondLookAtNativeText() throws IOException {
        // The same skipped amount in a text layer: exact as printed, so a second reading has nothing
        // to recover and is never asked for.
        var nativeOnly = new DocumentTextAcquirer() {
            int secondLooks;
            final List<PositionedText> runs = new PdfTextExtractor().extract(PdfFixtureBuilder.buildAmountOnLastLineSample());

            @Override public AcquiredDocument acquire(byte[] fileBytes, String password) { return AcquiredDocument.of(runs); }
            @Override public Optional<AcquiredDocument> secondLook(byte[] fileBytes, String password) { secondLooks++; return Optional.empty(); }
            @Override public boolean supports(byte[] fileBytes) { return true; }
        };

        var result = read(nativeOnly);

        assertThat(nativeOnly.secondLooks).isZero();
        assertThat(result.documentContext().diagnostics()).contains(PdfPreviewGenerator.BALANCE_MOVED_WITHOUT_AN_AMOUNT);
    }

    // ---- each requirement on its own: a second reading failing exactly one of them is refused ----

    private static StagedRow row(String amount) {
        return new StagedRow(java.time.LocalDate.of(2026, 9, 1), "SAMPLE", new BigDecimal(amount), "EXPENSE",
                "Other", "default", null, false, null, null);
    }

    private static com.finora.dto.ImportDto.VerificationReport report(String chain, String totals, boolean headerUncertain) {
        return new com.finora.dto.ImportDto.VerificationReport(List.of(
                new com.finora.dto.ImportDto.VerificationFinding("BALANCE_CHAIN", chain, java.util.Map.of()),
                new com.finora.dto.ImportDto.VerificationFinding("STATEMENT_TOTALS", totals, java.util.Map.of())),
                headerUncertain, "OCR", null);
    }

    private static PdfPreviewGenerator.PdfGenerationResult reading(int rowCount, int sections,
            com.finora.dto.ImportDto.VerificationReport report, String... diagnostics) {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        for (String d : diagnostics) ctx.recordDiagnostic(d);
        List<StagedRow> rows = java.util.stream.IntStream.range(0, rowCount).mapToObj(i -> row("100.00")).toList();
        List<com.finora.dto.ImportDto.StagedAccountSection> all = java.util.stream.IntStream.range(0, sections)
                .mapToObj(i -> new com.finora.dto.ImportDto.StagedAccountSection(null, rows, rows.size(), 0, List.of(), report))
                .toList();
        return new PdfPreviewGenerator.PdfGenerationResult(all, ctx, null);
    }

    private static final PdfPreviewGenerator.PdfGenerationResult FIRST =
            reading(4, 1, report("WARNING", "NOT_APPLICABLE", false), PdfPreviewGenerator.BALANCE_MOVED_WITHOUT_AN_AMOUNT);

    @Test
    void aSecondReadingMeetingEveryRequirementIsProven() {
        assertThat(PdfPreviewGenerator.secondReadingProven(FIRST, reading(5, 1, report("VERIFIED", "VERIFIED", false))))
                .isTrue();
    }

    @Test
    void aSecondReadingStillSkippingAnAmountIsNotProven() {
        assertThat(PdfPreviewGenerator.secondReadingProven(FIRST, reading(5, 1, report("VERIFIED", "VERIFIED", false),
                PdfPreviewGenerator.BALANCE_MOVED_WITHOUT_AN_AMOUNT))).isFalse();
    }

    @Test
    void aSecondReadingWithADifferentNumberOfAccountsIsNotProven() {
        assertThat(PdfPreviewGenerator.secondReadingProven(FIRST, reading(5, 2, report("VERIFIED", "VERIFIED", false))))
                .isFalse();
    }

    @Test
    void aSecondReadingWithFewerRowsIsNotProven() {
        assertThat(PdfPreviewGenerator.secondReadingProven(FIRST, reading(3, 1, report("VERIFIED", "VERIFIED", false))))
                .isFalse();
    }

    @Test
    void aSecondReadingWhoseChainDoesNotVerifyIsNotProven() {
        assertThat(PdfPreviewGenerator.secondReadingProven(FIRST, reading(5, 1, report("WARNING", "VERIFIED", false))))
                .isFalse();
    }

    @Test
    void aSecondReadingWhoseTotalsDoNotVerifyIsNotProven() {
        assertThat(PdfPreviewGenerator.secondReadingProven(FIRST, reading(5, 1, report("VERIFIED", "NOT_APPLICABLE", false))))
                .isFalse();
    }

    @Test
    void aSecondReadingTheTrustRulesWouldHoldIsNotProven() {
        // Chain and totals verified, but its column header had to be guess-rebuilt: held regardless.
        assertThat(PdfPreviewGenerator.secondReadingProven(FIRST, reading(5, 1, report("VERIFIED", "VERIFIED", true))))
                .isFalse();
    }

    @Test
    void noSecondReadingAvailableLeavesTheFirstStanding() throws IOException {
        var scan = new FakeScan(PdfFixtureBuilder.buildAmountOnLastLineSample(), null);

        var result = read(scan);

        assertThat(scan.secondLooks).isEqualTo(1);
        assertThat(result.sections().get(0).unparseableRows())
                .anyMatch(u -> u.reason().contains("running balance changed"));
    }
}
