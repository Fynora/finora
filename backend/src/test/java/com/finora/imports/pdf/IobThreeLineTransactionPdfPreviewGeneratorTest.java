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

/** End to end: an IOB-style statement keeps its reference number and bracketed value date out of the
 *  narration (plan 4, audit F-26). */
class IobThreeLineTransactionPdfPreviewGeneratorTest {

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
    void theReferenceAndTheValueDate_stayOutOfTheNarration() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "statement.pdf", PdfFixtureBuilder.buildIobStyleThreeLineTransactionsSample());

        assertThat(result.sections()).hasSize(1);
        var rows = result.sections().get(0).rows();
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(r -> r.description())
                .noneMatch(d -> d.matches(".*\\bS1000000\\d\\b.*"))
                .noneMatch(d -> d.contains("(13-Jul-26)") || d.contains("(22-Jul-26)"))
                .anyMatch(d -> d.contains("SAMPLE TWO PAYEE/BAR/Payment for rent"));
        assertThat(rows).extracting(r -> r.referenceNumber()).contains("S10000001", "S10000002", "S10000003", "S10000004");
    }
}
