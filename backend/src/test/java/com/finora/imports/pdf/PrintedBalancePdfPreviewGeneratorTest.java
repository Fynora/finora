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

/** End to end: a ledger whose balance chain yields nothing takes the statement's printed balances. */
class PrintedBalancePdfPreviewGeneratorTest {

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
    void aLedgerWithNoBalanceColumn_takesThePrintedOpeningAndClosingBalance() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "savings.pdf", PdfFixtureBuilder.buildLedgerWithoutBalanceColumnUnderAPrintedSummarySample());

        assertThat(result.sections()).hasSize(1);
        var detected = result.sections().get(0).detectedAccount();
        assertThat(detected.detectedProduct()).isEqualTo("SAVINGS");
        assertThat(result.sections().get(0).rows()).hasSize(2);
        assertThat(detected.openingBalance()).isEqualByComparingTo("10728.84");
        assertThat(detected.closingBalance()).isEqualByComparingTo("19447.84");
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("PRINTED_OPENING_CLOSING_BALANCE", "PRINTED_BALANCE_USED_AS_OPENING", "PRINTED_BALANCE_USED_AS_CLOSING");
    }

    @Test
    void aCardStatement_neverTakesItsPreviousBalanceAsAnOpeningBalance() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildCardStatementWithPrintedOpeningBalanceSample());

        assertThat(result.sections().get(0).detectedAccount().detectedProduct()).isEqualTo("CREDIT_CARD");
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .as("the printed figure WAS read; the card guard withholds it")
                .contains("PRINTED_OPENING_CLOSING_BALANCE")
                .doesNotContain("PRINTED_BALANCE_USED_AS_OPENING");
        assertThat(result.sections().get(0).detectedAccount().openingBalance()).isNull();
    }
}
