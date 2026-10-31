package com.finora.imports.convert;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sid's rule for the converter: a spreadsheet only when the statement's own figures confirm it. */
class AmountsConfirmedTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    private static StagedRow row() {
        return new StagedRow(LocalDate.of(2026, 9, 3), "SYNTHETIC PAYEE", new BigDecimal("100.00"), "EXPENSE",
                null, "none", null, false, null, null, null, null, null, null, null, null, 1);
    }

    private static StagedAccountSection section(List<StagedRow> rows, VerificationFinding... findings) {
        return new StagedAccountSection(null, rows, rows.size(), 0, List.of(),
                new VerificationReport(List.of(findings)));
    }

    private static VerificationFinding finding(String rule, String outcome) {
        return new VerificationFinding(rule, outcome, Map.of());
    }

    @Test
    void eachAmountProofOnItsOwnIsEnough() {
        for (String rule : List.of("STATEMENT_TOTALS", "BALANCE_CHAIN", "CREDIT_CARD_FLOW_RECONCILIATION")) {
            AmountsConfirmed.Verdict verdict = AmountsConfirmed.of(List.of(section(List.of(row()), finding(rule, "VERIFIED"))), TODAY);
            assertThat(verdict.confirmed()).as(rule).isTrue();
            assertThat(verdict.proofs()).containsExactly(rule);
        }
    }

    @Test
    void noCheckThatProvesAmounts_isNotConfirmed_evenWithNothingWrong() {
        AmountsConfirmed.Verdict verdict = AmountsConfirmed.of(List.of(section(List.of(row()),
                finding("SUMMARY_TOTALS", "VERIFIED"), finding("STATEMENT_TOTALS", "NOT_APPLICABLE"))), TODAY);

        assertThat(verdict.confirmed()).isFalse();
        assertThat(verdict.reasons()).containsExactly("NO_AMOUNT_PROOF");
    }

    @Test
    void anythingTheTrustRulesWouldHoldIsNotConfirmed_evenWithAnotherProofVerified() {
        // A card whose rows disagree with its printed totals is held by TrustPredicate (rule 8);
        // a verified balance chain beside it does not make the converter hand it over.
        AmountsConfirmed.Verdict verdict = AmountsConfirmed.of(List.of(section(List.of(row()),
                finding("BALANCE_CHAIN", "VERIFIED"), finding("CREDIT_CARD_FLOW_RECONCILIATION", "WARNING"))), TODAY);

        assertThat(verdict.confirmed()).isFalse();
        assertThat(verdict.reasons()).containsExactly("HOLD_CARD_ROWS_DISAGREE_WITH_SUMMARY");
    }

    @Test
    void aHeaderRebuiltWithLowConfidenceIsHeld_andSoNotConfirmed() {
        StagedAccountSection shaky = new StagedAccountSection(null, List.of(row()), 1, 0, List.of(),
                new VerificationReport(List.of(finding("BALANCE_CHAIN", "VERIFIED")), true, null, null));

        AmountsConfirmed.Verdict verdict = AmountsConfirmed.of(List.of(shaky), TODAY);

        assertThat(verdict.reasons()).contains("HOLD_HEADER_RECONSTRUCTION_UNCERTAIN");
    }

    @Test
    void damagedContentIsNotConfirmed_theConverterHasNobodyToAsk() {
        AmountsConfirmed.Verdict verdict = AmountsConfirmed.of(List.of(section(List.of(row()),
                finding("STATEMENT_TOTALS", "VERIFIED"), finding("CONTENT_INTEGRITY", "FAILED"))), TODAY);

        assertThat(verdict.reasons()).containsExactly("CONTENT_DAMAGED");
    }

    @Test
    void everySectionWithRowsNeedsItsOwnProof_emptySectionsAreSkipped() {
        StagedAccountSection proven = section(List.of(row()), finding("STATEMENT_TOTALS", "VERIFIED"));
        StagedAccountSection unproven = section(List.of(row()));
        StagedAccountSection empty = section(List.of());

        assertThat(AmountsConfirmed.of(List.of(proven, empty), TODAY).confirmed()).isTrue();
        assertThat(AmountsConfirmed.of(List.of(proven, unproven), TODAY).reasons()).containsExactly("NO_AMOUNT_PROOF");
    }

    @Test
    void noRowsAtAllIsNotConfirmed() {
        assertThat(AmountsConfirmed.of(List.of(section(List.of())), TODAY).reasons()).containsExactly("NO_ROWS");
        assertThat(AmountsConfirmed.of(List.of(), TODAY).reasons()).containsExactly("NO_ROWS");
    }

    @Test
    void aSectionWithNoReportAtAllIsNotConfirmed() {
        StagedAccountSection noReport = new StagedAccountSection(null, List.of(row()), 1, 0, List.of(), null);

        assertThat(AmountsConfirmed.of(List.of(noReport), TODAY).reasons()).containsExactly("NO_AMOUNT_PROOF");
    }
}
