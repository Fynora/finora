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
 * A printed credit limit, and a printed payment due date, are credit-card facts. Each is attached to
 * a section only when that section
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
        assertThat(savings.detectedAccount().paymentDueDate())
                .as("the card's payment due date printed on the same page is withheld the same way")
                .isNull();
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

    @Test
    void inAMultiSectionDocument_theGridFactsGoToTheSoleSectionThatCanBeACard() throws Exception {
        // The card section of a composite statement often classifies UNKNOWN (no balance column,
        // sparse vocabulary). Gated on "CREDIT_CARD or the document's sole section", the limit, the
        // due date and the grid card number were lost. They belong to the one section that could be
        // the card, never to the savings ledger beside it.
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "relationship.pdf", PdfFixtureBuilder.buildCardGridWithSavingsAndUnclassifiedTablesSample(false));

        assertThat(result.sections()).extracting(s -> s.detectedAccount().detectedProduct())
                .containsExactly("SAVINGS", "UNKNOWN");
        StagedAccountSection savings = result.sections().get(0);
        StagedAccountSection candidate = result.sections().get(1);
        assertThat(savings.detectedAccount().creditLimit()).isNull();
        assertThat(savings.detectedAccount().paymentDueDate()).isNull();
        assertThat(savings.detectedAccount().accountNumberMasked()).as("its own banner number, not the card's").endsWith("0001");
        assertThat(candidate.detectedAccount().creditLimit()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(candidate.detectedAccount().paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 20));
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("CARD_GRID_FACTS_ATTACHED_TO_SOLE_UNKNOWN_SECTION");
    }

    @Test
    void withTwoSectionsThatCouldBeTheCard_theGridFactsAttachNowhere() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "relationship.pdf", PdfFixtureBuilder.buildCardGridWithSavingsAndUnclassifiedTablesSample(true));

        assertThat(result.sections()).extracting(s -> s.detectedAccount().detectedProduct())
                .containsExactly("SAVINGS", "UNKNOWN", "UNKNOWN");
        assertThat(result.sections()).allSatisfy(s -> {
            assertThat(s.detectedAccount().creditLimit()).isNull();
            assertThat(s.detectedAccount().paymentDueDate()).isNull();
        });
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("CARD_GRID_FACTS_WITHHELD_AMBIGUOUS")
                .doesNotContain("CARD_GRID_FACTS_ATTACHED_TO_SOLE_UNKNOWN_SECTION");
    }

    @Test
    void aSavingsSectionWithoutItsOwnNumber_neverTakesTheGridCardNumber() throws Exception {
        // The grid number is a card fact like the limit: a ledger with no number of its own must
        // not borrow the card's, or the account it creates carries another product's identity.
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "relationship.pdf", PdfFixtureBuilder.buildSavingsLedgerWithoutOwnNumberBesideCardGridSample());
        assertThat(result.sections()).hasSize(1);
        StagedAccountSection savings = result.sections().get(0);

        assertThat(savings.detectedAccount().detectedProduct()).isEqualTo("SAVINGS");
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .as("the grid WAS read").contains("PRINTED_ACCOUNT_NUMBER_GRID");
        assertThat(savings.detectedAccount().accountNumberMasked()).isNull();
    }

    @Test
    void aSavingsSectionWithoutItsOwnNumber_takesAGridNumberLabelledAsAnAccountNumber() throws Exception {
        // Measured on a real HSBC composite: its portfolio grid lists the savings account's own
        // number under "Account Number", and the savings section has no other source for it. A
        // label-blind gate dropped that number; only a card-labelled grid number is withheld.
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "relationship.pdf", PdfFixtureBuilder.buildSavingsLedgerWithoutOwnNumberBesideAccountNumberGridSample());
        StagedAccountSection savings = result.sections().get(0);

        assertThat(savings.detectedAccount().detectedProduct()).isEqualTo("SAVINGS");
        assertThat(savings.detectedAccount().accountNumberMasked()).endsWith("7890");
        assertThat(savings.detectedAccount().creditLimit()).as("the limit beside it is still a card fact").isNull();
    }
}
