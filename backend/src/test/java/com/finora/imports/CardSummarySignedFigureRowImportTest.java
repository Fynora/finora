package com.finora.imports;

import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.trust.TrustPredicate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card whose summary figures are joined by the signs of its billing equation
 * (CardSummarySignedFigureRowReader), from the bytes of the statement to the two card rules and the
 * decision to hold or release the import. Before this, such a statement read as a total due and
 * nothing else: both rules reported NOT_APPLICABLE and its amounts were compared with nothing the
 * statement prints.
 */
class CardSummarySignedFigureRowImportTest {

    private static PdfPreviewGenerator.PdfGenerationResult stage(boolean aPurchaseRowIsMissing) throws Exception {
        return PdfGeneratorTestSupport.generator().generateSectionsWithContext(UUID.randomUUID(), "card.pdf",
                PdfFixtureBuilder.buildCardWithSignedFigureRowSummarySample(aPurchaseRowIsMissing));
    }

    private static VerificationFinding finding(VerificationReport report, String rule) {
        return report.findings().stream().filter(f -> rule.equals(f.rule())).findFirst().orElseThrow();
    }

    private static boolean held(VerificationReport report) {
        return TrustPredicate.evaluate(List.of(report), null, LocalDate.of(2026, 7, 1)).hold();
    }

    @Test
    void aStatementWhoseSummaryAddsUpAndWhoseRowsMatchIt_verifiesOnBothCardRules() throws Exception {
        var result = stage(false);
        var section = result.sections().get(0);
        var report = section.verification();

        assertThat(section.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(section.rows()).hasSize(3);
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("CREDIT_CARD_SUMMARY_SIGNED_FIGURE_ROW");
        var totals = finding(report, "CREDIT_CARD_STATEMENT_TOTALS");
        assertThat(totals.outcome()).isEqualTo("VERIFIED");
        assertThat(totals.details().get("extractionMethod")).isEqualTo("SIGNED_FIGURE_ROW");
        assertThat(finding(report, "CREDIT_CARD_FLOW_RECONCILIATION").outcome()).isEqualTo("VERIFIED");
        assertThat(report.reliabilityStatus()).isEqualTo(ImportReliabilityStatus.CLEAN);
        assertThat(held(report)).isFalse();
        // The rows carry the printed previous balance to the total due, so the account opens there
        // -- at the rupee figure, not at the points count the rewards box calls "Opening Balance".
        assertThat(section.detectedAccount().openingBalance()).isEqualByComparingTo("9000.00");
        assertThat(section.detectedAccount().totalAmountDue()).isEqualByComparingTo("4250.00");
    }

    @Test
    void aStatementMissingAPurchaseRow_isHeldForReview() throws Exception {
        var section = stage(true).sections().get(0);
        var report = section.verification();

        assertThat(section.rows()).hasSize(2);
        assertThat(finding(report, "CREDIT_CARD_STATEMENT_TOTALS").outcome()).isEqualTo("VERIFIED");
        assertThat(finding(report, "CREDIT_CARD_FLOW_RECONCILIATION").outcome()).isEqualTo("WARNING");
        assertThat(held(report)).isTrue();
        // Rows that do not reach the total due are not trusted to open the account either.
        assertThat(section.detectedAccount().openingBalance()).isNull();
    }
}
