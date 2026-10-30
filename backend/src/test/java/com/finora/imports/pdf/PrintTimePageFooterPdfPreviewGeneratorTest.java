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
 * Punjab National Bank statements end every page with the time they were printed and the page
 * number. The page number sits outside the table, so the stamp alone was appended to the last
 * transaction of each page, and the ledger showed it as part of that transaction's narration.
 */
class PrintTimePageFooterPdfPreviewGeneratorTest {

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
    void thePrintTimeFooterIsNotGluedOntoThePagesLastTransaction() throws Exception {
        StagedAccountSection section = read(PdfFixtureBuilder.buildPrintTimePageFooterSample()).sections().get(0);

        assertThat(section.rows()).extracting(r -> r.description().replace('\n', ' '))
                .containsExactlyInAnyOrder("UPI/DR/SAMPLE PAYEE ONE", "NEFT_IN:SAMPLEREF01//SAMPLE PAYER",
                        "UPI/DR/SAMPLE PAYEE TWO", "UPI/CR/SAMPLE PAYER TWO");
        assertThat(section.rows()).extracting(r -> r.amount())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("500.00"), new BigDecimal("2000.00"),
                        new BigDecimal("1500.00"), new BigDecimal("250.00"));
        assertThat(section.unparseableRows()).isEmpty();
    }

    @Test
    void rowAccountingAndTheBalanceChainStayVerified() throws Exception {
        var section = read(PdfFixtureBuilder.buildPrintTimePageFooterSample()).sections().get(0);

        assertThat(section.verification().findings())
                .anyMatch(f -> "ROW_ACCOUNTING".equals(f.rule()) && "VERIFIED".equals(f.outcome()));
        assertThat(section.verification().findings())
                .anyMatch(f -> "BALANCE_CHAIN".equals(f.rule()) && "VERIFIED".equals(f.outcome()));
    }
}
