package com.finora.imports.pdf;

import com.finora.imports.TestAccountRepositories;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.DuplicateDetector;
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

/**
 * Phase 1B: {@code CreditCardSummaryEvidence.totalAmountDue} was already correctly detected by
 * {@link CreditCardSummaryExtractor} but went no further -- {@link CreditCardStatementTotalsValidator}
 * read it and nothing else did, so it never reached {@link com.finora.dto.ImportDto.DetectedAccountInfo},
 * the API, or the review screen. This is the plumbing, not a new detection capability: the same
 * evidence {@code buildLedgerSection} already threads to {@code importVerifier.verify(...)} now
 * also reaches {@code buildDetectedAccountInfo}.
 */
class TotalAmountDuePlumbingPdfPreviewGeneratorTest {

    private PdfPreviewGenerator realGenerator() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository, TestAccountRepositories.anyLive());
        TransactionNormalizer transactionNormalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());

        return new PdfPreviewGenerator(new PdfTextExtractor(), new PdfTableLocator(),
                new PdfMetadataExtractor(), transactionNormalizer, com.finora.imports.product.ProductDiscovery.standard(),
                new com.finora.imports.product.ProductAttributeExtractor(),
                new com.finora.imports.ImportVerifier(new com.finora.imports.BalanceChainValidator(),
                        new com.finora.imports.StatementTotalsValidator(), new com.finora.imports.SummaryTotalsValidator(),
                        new com.finora.imports.ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(),
                        new com.finora.imports.CreditCardFlowReconciliationValidator(), new com.finora.imports.DescriptionCorruptionValidator()),
                com.finora.imports.TestRuleEngines.empty());
    }

    /**
     * A payment-summary panel in the same-row label/value grid shape {@code CreditCardSummaryExtractor}
     * already reads today (see {@code CreditCardSummaryExtractorTest.sameRowSummaryBlock}); this test
     * proves that value now survives all the way into {@code DetectedAccountInfo}, not just into the
     * verification report {@code CreditCardStatementTotalsValidator} already consumed it for.
     */
    @Test
    void aCreditCardStatementsTotalAmountDue_reachesDetectedAccountInfo() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildCreditCardTotalDueGridSample();

        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "credit_card_total_due_statement.pdf", pdf);

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(response.detectedAccount().totalAmountDue()).isEqualByComparingTo("27665.16");
    }

    /**
     * A savings account has no payment-summary panel for {@code CreditCardSummaryExtractor} to
     * read at all -- {@code totalAmountDue} must stay null rather than picking up an unrelated
     * figure from the statement (a transaction amount, a running balance, ...).
     */
    @Test
    void aNonCreditCardStatement_leavesTotalAmountDueNull() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildIncidentalCardNumberSecurityNoticeSample();

        StagingResponse response = realGenerator().generate(UUID.randomUUID(), "savings_with_security_notice.pdf", pdf);

        assertThat(response.detectedAccount().suggestedAccountType()).isEqualTo("SAVINGS");
        assertThat(response.detectedAccount().totalAmountDue()).isNull();
    }

    // --- A card's opening balance ---

    @Test
    void aCardWhoseRowsCarryThePreviousBalanceToTheTotalDue_opensAtThePrintedPreviousBalance() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildCreditCardStatementWhoseRowsReconcileSample());

        var detected = result.sections().get(0).detectedAccount();
        assertThat(detected.suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(result.sections().get(0).rows()).hasSize(3);
        assertThat(detected.openingBalance()).isEqualByComparingTo("20000.00");
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("PRINTED_PREVIOUS_BALANCE_USED_AS_CARD_OPENING");
    }

    @Test
    void aCardWhoseRowsDoNotReachTheTotalDue_leavesTheOpeningBalanceToTheReviewScreen() throws Exception {
        // One purchase against a summary that moved by far more: the rows are not the whole cycle.
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildCreditCardTotalDueGridSample());

        assertThat(result.sections().get(0).detectedAccount().openingBalance()).isNull();
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .doesNotContain("PRINTED_PREVIOUS_BALANCE_USED_AS_CARD_OPENING");
    }

    // --- HSBC's payment box ---

    @Test
    void theUnlabelledPaymentBoxsTotal_isTheTotalAmountDue_notTheNetOutstandingBalance() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildCardWithUnlabelledPaymentBoxSample(true));

        // Only the box's geometry is reproduced here, not every signal the real statement uses to
        // name its product, so the account type is not what this test checks.
        assertThat(result.sections().get(0).detectedAccount().totalAmountDue()).isEqualByComparingTo("6000.00");
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("CARD_PAYMENT_SUMMARY_UNLABELLED_VALUES");
    }

    @Test
    void withoutThePaymentBox_theNetOutstandingBalanceStaysTheFallback() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildCardWithUnlabelledPaymentBoxSample(false));

        assertThat(result.sections().get(0).detectedAccount().totalAmountDue()).isEqualByComparingTo("9000.00");
    }

    // --- A composite statement: the card's summary belongs only to the card ---

    private static String outcomeOf(com.finora.dto.ImportDto.StagedAccountSection section, String rule) {
        return section.verification().findings().stream().filter(f -> rule.equals(f.rule()))
                .map(com.finora.dto.ImportDto.VerificationFinding::outcome).findFirst().orElse(null);
    }

    @Test
    void aSavingsLedgerBesideACard_isNeitherCheckedAgainstNorGivenTheCardsSummary() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "composite.pdf", PdfFixtureBuilder.buildCardSummaryWithSavingsAndOneUnclassifiedTableSample());

        var savings = result.sections().stream()
                .filter(s -> "SAVINGS".equals(s.detectedAccount().detectedProduct())).findFirst().orElseThrow();
        assertThat(savings.detectedAccount().totalAmountDue()).isNull();
        assertThat(outcomeOf(savings, "CREDIT_CARD_FLOW_RECONCILIATION")).isEqualTo("NOT_APPLICABLE");
        assertThat(outcomeOf(savings, "CREDIT_CARD_STATEMENT_TOTALS")).isEqualTo("NOT_APPLICABLE");

        // The only section that can be the card still gets the summary's total due.
        var card = result.sections().stream()
                .filter(s -> "UNKNOWN".equals(s.detectedAccount().detectedProduct())).findFirst().orElseThrow();
        assertThat(card.detectedAccount().totalAmountDue()).isEqualByComparingTo("3057.02");

        assertThat(com.finora.imports.trust.TrustPredicate.evaluate(
                result.sections().stream().map(s -> s.verification()).toList(), java.util.List.of(),
                java.time.LocalDate.of(2026, 9, 1)).hold()).isFalse();
    }

    @Test
    void aCompositeWhoseCardCannotBeTold_holdsNothingAndGivesNoSectionTheTotalDue() throws Exception {
        PdfPreviewGenerator.PdfGenerationResult result = realGenerator().generateSectionsWithContext(
                UUID.randomUUID(), "composite.pdf", PdfFixtureBuilder.buildSavingsAndCardWithCardSummarySample());

        assertThat(result.sections()).hasSize(2);
        assertThat(result.sections()).allSatisfy(s -> assertThat(s.detectedAccount().totalAmountDue()).isNull());
        assertThat(com.finora.imports.trust.TrustPredicate.evaluate(
                result.sections().stream().map(s -> s.verification()).toList(), java.util.List.of(),
                java.time.LocalDate.of(2026, 9, 1)).hold()).isFalse();
    }
}
