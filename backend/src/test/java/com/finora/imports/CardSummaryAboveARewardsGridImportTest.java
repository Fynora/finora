package com.finora.imports;

import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.trust.TrustPredicate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card whose summary sits above a rewards grid headed "Opening Balance", and whose previous dues
 * are a few paise in the customer's favour -- from the bytes of the statement to the two card
 * rules, the opening balance and the decision to hold or release the import. Before this, the
 * rewards heading made the previous balance ambiguous, the summary was dropped whole, and both
 * rules reported NOT_APPLICABLE: the statement's amounts were compared with nothing it prints.
 */
class CardSummaryAboveARewardsGridImportTest {

    private static PdfPreviewGenerator.PdfGenerationResult stage(boolean aPurchaseRowIsMissing) throws Exception {
        return PdfGeneratorTestSupport.generator().generateSectionsWithContext(UUID.randomUUID(), "card.pdf",
                PdfFixtureBuilder.buildCardWithSummaryAboveARewardsGridSample(aPurchaseRowIsMissing));
    }

    private static VerificationFinding finding(VerificationReport report, String rule) {
        return report.findings().stream().filter(f -> rule.equals(f.rule())).findFirst().orElseThrow();
    }

    private static boolean held(VerificationReport report) {
        return TrustPredicate.evaluate(List.of(report), null, LocalDate.of(2026, 7, 1)).hold();
    }

    @Test
    void theSummaryIsReadAndBothCardRulesVerify() throws Exception {
        var section = stage(false).sections().get(0);
        var report = section.verification();

        assertThat(section.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(section.rows()).hasSize(2);
        var totals = finding(report, "CREDIT_CARD_STATEMENT_TOTALS");
        assertThat(totals.outcome()).isEqualTo("VERIFIED");
        // -0.40 + 6,200.30 = 6,199.90, against a total printed in whole rupees as 6,200.00.
        assertThat((BigDecimal) totals.details().get("previousBalance")).isEqualByComparingTo("-0.40");
        assertThat((BigDecimal) totals.details().get("expectedTotalAmountDue")).isEqualByComparingTo("6199.90");
        assertThat(totals.details().get("totalAmountDueRoundedToRupee")).isEqualTo(true);
        assertThat(finding(report, "CREDIT_CARD_FLOW_RECONCILIATION").outcome()).isEqualTo("VERIFIED");
        assertThat(report.reliabilityStatus()).isEqualTo(ImportReliabilityStatus.CLEAN);
        assertThat(held(report)).isFalse();
        assertThat(section.detectedAccount().totalAmountDue()).isEqualByComparingTo("6200.00");
    }

    @Test
    void theAccountOpensFortyPaiseInTheCustomersFavour_notFortyPaiseOwed() throws Exception {
        var detected = stage(false).sections().get(0).detectedAccount();

        // Read as a magnitude this was +0.40: eighty paise from where the statement says it began.
        assertThat(detected.openingBalance()).isEqualByComparingTo("-0.40");
    }

    @Test
    void aStatementMissingAPurchaseRow_isHeldForReview() throws Exception {
        var section = stage(true).sections().get(0);
        var report = section.verification();

        assertThat(section.rows()).hasSize(1);
        assertThat(finding(report, "CREDIT_CARD_STATEMENT_TOTALS").outcome()).isEqualTo("VERIFIED");
        assertThat(finding(report, "CREDIT_CARD_FLOW_RECONCILIATION").outcome()).isEqualTo("WARNING");
        assertThat(held(report)).isTrue();
        // Rows that do not reach the total due are not trusted to open the account either.
        assertThat(section.detectedAccount().openingBalance()).isNull();
    }
}
