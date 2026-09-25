package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.DocumentContext;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.acquisition.DocumentTextAcquirer;
import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-document proof, from a redacted trace of a real IndusInd (CRED RuPay) credit-card
 * statement, for three defects one upload surfaced together -- reported by the statement's owner as
 * "the holder name is Previous Balance" and "only 20 rows were parsed":
 *
 * <ul>
 *   <li>The right-margin summary panel prints at the same heights as the ledger. Its "Statement
 *       Date" value landed on the ledger's own "Total 0 1,285.00" sub-table total row, defeating
 *       the whole-line PAGE_LEGEND_BLOCK_START match written for that row; the total row was then
 *       merged into the payment above it as a wrapped description. Its "Total Outstanding" figure
 *       landed on the first purchase's row and was appended to that purchase's amount, which made
 *       the amount unparseable and dropped the row -- 20 staged of 21 printed. See
 *       {@code PdfTableLocator.MarginPanelBand} and {@code tableRunsOf}.</li>
 *   <li>The banner that opens each sub-table ("Payment Details for &lt;holder&gt; (Credit Card No.
 *       &lt;masked&gt;)") is the only place the document prints the holder and the card number, and
 *       it sits below the column header where the metadata extractor never looked. See
 *       {@code PdfTableLocator.CARDHOLDER_SUBTABLE_BANNER} and
 *       {@code PdfMetadataExtractor.CARDHOLDER_FOR_BANNER}.</li>
 *   <li>With no holder found, the unlabelled leading-line fallback captured the document's first
 *       summary-grid label. See {@code PdfMetadataExtractor.SUMMARY_GRID_VALUE_LINE}.</li>
 * </ul>
 *
 * <p>The trace preserves the geometry exactly and the structural vocabulary the three mechanisms
 * key on (see the redactor's own allowlist); names, narrations and identifiers are masked, so the
 * holder assertion below is against the masked banner text, not a real name.
 */
class MarginPanelCollisionRegressionTest {

    private static final String TRACE = "indusind-credit-card-margin-panel-collision";

    @Test
    void everyPrintedTransactionIsStaged_andNoneCarriesThePanelOrTotalRowText() {
        List<StagedAccountSection> sections = generate();

        assertThat(sections).hasSize(1);
        StagedAccountSection section = sections.get(0);
        // 1 payment + 20 purchases, the counts the statement's own summary panel reconciles to.
        assertThat(section.rows()).hasSize(21);
        assertThat(section.unparseableRows()).isEmpty();

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (var row : section.rows()) {
            assertThat(row.description()).doesNotContain("Total").doesNotContain("Credit Card No");
            if ("INCOME".equals(row.type())) credits = credits.add(row.amount());
            else debits = debits.add(row.amount());
        }
        // The panel's own printed figures: "Purchases & Other Charges 2,429.08" and
        // "Payments & Other Credits 1,285.00" -- amounts are preserved verbatim by redaction.
        assertThat(debits).isEqualByComparingTo("2429.08");
        assertThat(credits).isEqualByComparingTo("1285.00");
    }

    @Test
    void theHolderAndCardNumberComeFromTheSubTableBanner_notFromTheSummaryGrid() {
        StagedAccountSection section = generate().get(0);

        // "Previous Balance" redacts to "Xxxxxxxx Balance"; either way it must not be the holder.
        assertThat(section.detectedAccount().accountHolderName())
                .isEqualTo("XX XXXXXXXXX XXXXXX")
                .doesNotContainIgnoringCase("balance");
        assertThat(section.detectedAccount().accountNumberMasked()).isEqualTo("9999XXXXXXXX9999");
    }

    @Test
    void theThreeMechanismsActivateOnTheTrace_andTheTotalRowIsRecognised() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(PdfTrace.load(TRACE), ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("MARGIN_PANEL_TEXT_EXCLUDED", "PAGE_LEGEND_BLOCK_SUPPRESSED", "CARDHOLDER_SUBTABLE_BANNER");
        assertThat(doc.sections()).hasSize(1);
        for (Map<String, String> row : doc.sections().get(0).rows()) {
            assertThat(row.keySet()).as("a dateless row must not be located: " + row).contains("Date");
            assertThat(row.get("Date")).matches("\\d{2}/\\d{2}/\\d{4}");
        }
        assertThat(doc.sections().get(0).auxiliaryText())
                .anyMatch(line -> line.startsWith("Payment Details for"))
                .anyMatch(line -> line.contains("CRED Points Transferred*"));
    }

    private record TraceAcquirer(List<PositionedText> runs) implements DocumentTextAcquirer {
        @Override public AcquiredDocument acquire(byte[] fileBytes, String password) { return AcquiredDocument.of(runs); }
        @Override public boolean supports(byte[] fileBytes) { return true; }
    }

    private List<StagedAccountSection> generate() {
        try {
            return generator().generateSections(UUID.randomUUID(), TRACE + ".pdf", new byte[]{1}, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PdfPreviewGenerator generator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        TransactionNormalizer normalizer = new TransactionNormalizer(categorizationService,
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()), TestRuleEngines.empty());
        return new PdfPreviewGenerator(new TraceAcquirer(PdfTrace.load(TRACE)), new PdfTableLocator(),
                new PdfMetadataExtractor(), normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(), new com.finora.imports.DescriptionCorruptionValidator()),
                TestRuleEngines.empty());
    }
}
