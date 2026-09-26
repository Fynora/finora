package com.finora.imports.pdf;

import com.finora.imports.DuplicateDetector;
import com.finora.imports.TestAccountRepositories;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** End to end: a bank name printed at the same spot at the foot of every page is page furniture, never
 *  the leading narration of the next page's first transaction (plan 4, F-03). */
class RepeatedPageFurnitureLeadingPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
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
                new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive()),
                com.finora.imports.TestRuleEngines.empty());
        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                normalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    @Test
    void aFooterRepeatedOnEveryPage_isNotPrefixedOntoTheNextPagesFirstRow() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "ledger.pdf", PdfFixtureBuilder.buildLedgerWithBankNameFooterOnEveryPageSample());

        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).rows()).hasSize(5);
        assertThat(result.sections().get(0).rows()).extracting(r -> r.description())
                .noneMatch(d -> d.contains("SAMPLE BANK LIMITED"));
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("LEADING_BUFFER_REPEATED_PAGE_FURNITURE_DIVERTED");
    }

    @Test
    void theSameLineOnOnePageOnly_keepsTodaysHandling() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "ledger.pdf", PdfFixtureBuilder.buildLedgerWithBankNameFooterOnOnePageSample());

        assertThat(result.sections().get(0).rows()).hasSize(4);
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .doesNotContain("LEADING_BUFFER_REPEATED_PAGE_FURNITURE_DIVERTED");
    }

    @Test
    void aNarrationLineRepeatedAtTheSameHeightOnTwoOfFourPages_staysNarration() throws Exception {
        // Measured regression: a rule that took "same text, same height, two pages" as furniture
        // stripped real narration from about 180 rows on statements printed on a fixed row grid.
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "ledger.pdf", PdfFixtureBuilder.buildLedgerWithARecurringNarrationLineOnTwoPagesSample());

        assertThat(result.sections().get(0).rows()).extracting(r -> r.description())
                .filteredOn(d -> d.startsWith("UPI-SAMPLE PAYEE"))
                .hasSize(2)
                .allMatch(d -> d.contains("SENT FROM PHONE"));
    }
}
