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
 * A printed credit limit is a credit-card fact. It is attached to a section only when that section
 * is a credit card, or when the document is a single section whose product could not be
 * classified at all (a bare card statement the classifier did not recognise must keep its limit).
 *
 * <p>Found on a real composite relationship statement: its summary page lists the customer's
 * credit card with its limit beside the savings account whose transactions the document carries,
 * {@code CreditLimitGridExtractor} reads that grid document-wide, and the savings section was
 * staged with the card's limit -- a figure that has no meaning on a savings account and that the
 * review screen showed as the account's own.
 */
class SectionScopedCreditLimitPdfPreviewGeneratorTest {

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
    void aSavingsSection_neverCarriesTheCardLimitPrintedOnTheSameDocument() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "relationship.pdf", PdfFixtureBuilder.buildSavingsLedgerWithCardLimitGridSample());

        assertThat(result.sections()).hasSize(1);
        StagedAccountSection savings = result.sections().get(0);
        assertThat(savings.detectedAccount().detectedProduct()).isEqualTo("SAVINGS");
        assertThat(savings.rows()).as("the ledger itself is unaffected").hasSize(2);
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .as("the grid WAS read -- the limit is withheld, not missed")
                .contains("PRINTED_CREDIT_LIMIT_GRID", "CREDIT_LIMIT_WITHHELD_FROM_NON_CARD_SECTION");
        assertThat(savings.detectedAccount().creditLimit()).isNull();
    }

    @Test
    void aCreditCardStatement_keepsItsOwnLimit() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildMultiColumnPaymentSummaryGridSample());

        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).detectedAccount().detectedProduct()).isEqualTo("CREDIT_CARD");
        assertThat(result.sections().get(0).detectedAccount().creditLimit()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .doesNotContain("CREDIT_LIMIT_WITHHELD_FROM_NON_CARD_SECTION");
    }
}
