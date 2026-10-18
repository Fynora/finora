package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedAccountSection;
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
 * Two real scanned HSBC savings statements close their table with a summary block and then the
 * bank's address footer; both reached the review screen as unparseable rows. They are boilerplate:
 * suppressed as a page-boundary block, every transaction still staged, and row accounting still
 * verified because the suppressed lines are explained.
 */
class HsbcSummaryBlockAndFooterPdfPreviewGeneratorTest {

    private static PdfPreviewGenerator.PdfGenerationResult read(byte[] pdf) throws Exception {
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
        var generator = new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(),
                        new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
        return generator.generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf);
    }

    @Test
    void theSummaryBlockAndAddressFooterAreNotStagedAsUnparseableRows() throws Exception {
        var result = read(PdfFixtureBuilder.buildHsbcSummaryBlockAndFooterSample());
        StagedAccountSection section = result.sections().get(0);

        assertThat(section.unparseableRows()).isEmpty();
        assertThat(section.rows()).extracting(r -> r.amount())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("50000.00"), new BigDecimal("1000.00"), new BigDecimal("3000.00"));
        // The last transaction keeps its own narration: without the block, the summary's first line
        // was glued onto it ("ECS-SAMPLE Withdrawals 4,000.00DR As at 05SEP2026").
        assertThat(section.rows()).extracting(r -> r.description().replace('\n', ' '))
                .containsExactlyInAnyOrder("NEFT FROM SAMPLE PAYER", "UPI SAMPLE PAYEE ONE", "ECS-SAMPLE");
        assertThat(result.documentContext().capabilities())
                .extracting(com.finora.dto.ImportDto.CapabilityActivation::capability)
                .contains("PAGE_LEGEND_BLOCK_SUPPRESSED");
    }

    @Test
    void rowAccountingStaysVerifiedBecauseTheSuppressedLinesAreExplained() throws Exception {
        var section = read(PdfFixtureBuilder.buildHsbcSummaryBlockAndFooterSample()).sections().get(0);

        assertThat(section.verification().findings())
                .anyMatch(f -> "ROW_ACCOUNTING".equals(f.rule()) && "VERIFIED".equals(f.outcome()));
        assertThat(section.verification().findings())
                .anyMatch(f -> "BALANCE_CHAIN".equals(f.rule()) && "VERIFIED".equals(f.outcome()));
    }
}
