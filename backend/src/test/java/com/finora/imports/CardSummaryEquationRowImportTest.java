package com.finora.imports;

import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder.EquationRowCase;
import com.finora.imports.trust.TrustPredicate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card whose summary is its own billing equation on one line (CardSummaryEquationReader), from
 * the bytes of the statement to the two card rules and the decision to hold or release the import.
 * Before this, such a statement read as a total due and nothing else: both rules reported
 * NOT_APPLICABLE and its amounts were compared with nothing the statement prints.
 */
class CardSummaryEquationRowImportTest {

    private static PdfPreviewGenerator.PdfGenerationResult stage(EquationRowCase rowCase) throws Exception {
        return PdfGeneratorTestSupport.generator().generateSectionsWithContext(
                UUID.randomUUID(), "card.pdf", PdfFixtureBuilder.buildCardWithSummaryEquationRowSample(rowCase));
    }

    private static VerificationFinding finding(VerificationReport report, String rule) {
        return report.findings().stream().filter(f -> rule.equals(f.rule())).findFirst().orElseThrow();
    }

    private static boolean held(VerificationReport report) {
        return TrustPredicate.evaluate(List.of(report), null, LocalDate.of(2026, 7, 1)).hold();
    }

    @Test
    void aStatementWhoseSummaryAddsUpAndWhoseRowsMatchIt_verifiesOnBothCardRules() throws Exception {
        var result = stage(EquationRowCase.ADDS_UP);
        var section = result.sections().get(0);
        var report = section.verification();

        assertThat(section.detectedAccount().suggestedAccountType()).isEqualTo("CREDIT_CARD");
        assertThat(section.rows()).hasSize(5);
        assertThat(result.documentContext().capabilities()).extracting(c -> c.capability())
                .contains("CREDIT_CARD_SUMMARY_EQUATION_ROW");
        assertThat(finding(report, "CREDIT_CARD_STATEMENT_TOTALS").outcome()).isEqualTo("VERIFIED");
        assertThat(finding(report, "CREDIT_CARD_STATEMENT_TOTALS").details().get("extractionMethod"))
                .isEqualTo("EQUATION_ROW");
        assertThat(finding(report, "CREDIT_CARD_FLOW_RECONCILIATION").outcome()).isEqualTo("VERIFIED");
        assertThat(report.reliabilityStatus()).isEqualTo(ImportReliabilityStatus.CLEAN);
        assertThat(held(report)).isFalse();
        // The rows carry the printed previous balance to the total due, so the account opens there.
        assertThat(section.detectedAccount().openingBalance()).isEqualByComparingTo("4000.00");
        assertThat(section.detectedAccount().totalAmountDue()).isEqualByComparingTo("6800.00");
    }

    @Test
    void aRefundTheSummaryLeftOutOfItsCredits_isCheckedAgainstTheBalances_andRaisesNoWarning() throws Exception {
        var section = stage(EquationRowCase.SUMMARY_LEAVES_OUT_A_REFUND).sections().get(0);
        var report = section.verification();

        assertThat(section.rows()).hasSize(6);
        var rowCheck = finding(report, "CREDIT_CARD_FLOW_RECONCILIATION");
        assertThat(rowCheck.outcome()).isEqualTo("VERIFIED");
        assertThat((BigDecimal) rowCheck.details().get("creditsNotCountedInSummary")).isEqualByComparingTo("150.00");
        // The summary's own figures do not reach its total, and the rows show why: nothing was
        // misread, so nothing warns.
        var totals = finding(report, "CREDIT_CARD_STATEMENT_TOTALS");
        assertThat(totals.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat((String) totals.details().get("reason")).startsWith("SUMMARY_LEAVES_OUT_A_CREDIT");
        assertThat(report.reliabilityStatus()).isEqualTo(ImportReliabilityStatus.CLEAN);
        assertThat(held(report)).isFalse();
        assertThat(section.detectedAccount().openingBalance()).isEqualByComparingTo("4000.00");
    }

    @Test
    void aStatementMissingAPurchaseRow_isHeldForReview() throws Exception {
        var section = stage(EquationRowCase.A_PURCHASE_ROW_IS_MISSING).sections().get(0);
        var report = section.verification();

        assertThat(section.rows()).hasSize(4);
        assertThat(finding(report, "CREDIT_CARD_STATEMENT_TOTALS").outcome()).isEqualTo("VERIFIED");
        assertThat(finding(report, "CREDIT_CARD_FLOW_RECONCILIATION").outcome()).isEqualTo("WARNING");
        assertThat(held(report)).isTrue();
        // Rows that do not reach the total due are not trusted to open the account either.
        assertThat(section.detectedAccount().openingBalance()).isNull();
    }
}
