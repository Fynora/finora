package com.finora.imports;

import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A category the user changes on the import review screen is their decision, and has to be stored
 * as one -- a statement refresh keeps only the categories a user chose. The server tells by
 * comparing the confirmed category with the one it suggested for the same parsed row; see
 * {@link ConfirmedRowIntegrity#withStatementFacts}.
 */
class ConfirmedRowIntegrityReviewCategoryTest {

    private static final UUID RULE_ID = UUID.randomUUID();

    private static StagedRow staged(String description, String suggestedCategory) {
        return new StagedRow(LocalDate.of(2026, 9, 1), description, new BigDecimal("450.00"), "EXPENSE",
                suggestedCategory, "rule", RULE_ID, false, null, null, null, RowKind.TRANSACTION, null, null, null, 80);
    }

    private static ConfirmedRow confirmed(String description, String category) {
        return new ConfirmedRow(LocalDate.of(2026, 9, 1), description, new BigDecimal("450.00"), "EXPENSE",
                category, true, "rule", RULE_ID, false, null, null, false, 80, 3);
    }

    private static ConfirmedRow only(List<StagedRow> parsed, ConfirmedRow row) {
        return ConfirmedRowIntegrity.withStatementFacts(parsed, List.of(row)).get(0);
    }

    @Test
    void aCategoryChangedOnTheReviewScreen_isRecordedAsTheUsersChoice() {
        ConfirmedRow result = only(List.of(staged("UPI GROCER", "Shopping")), confirmed("UPI GROCER", "Groceries"));

        assertThat(result.categorySource()).isEqualTo(CategorizationService.REVIEW_SOURCE);
        assertThat(result.category()).isEqualTo("Groceries");
        // The rule and its confidence did not decide this row, the user did.
        assertThat(result.ruleId()).isNull();
        assertThat(result.categoryConfidence()).isNull();
        // Everything else is carried through untouched.
        assertThat(result.rowPosition()).isEqualTo(3);
        assertThat(result.include()).isTrue();
    }

    @Test
    void anUnchangedCategory_keepsTheEnginesSource() {
        ConfirmedRow result = only(List.of(staged("UPI GROCER", "Groceries")), confirmed("UPI GROCER", "Groceries"));

        assertThat(result.categorySource()).isEqualTo("rule");
        assertThat(result.ruleId()).isEqualTo(RULE_ID);
        assertThat(result.categoryConfidence()).isEqualTo(80);
    }

    @Test
    void aDifferenceOnlyInCaseOrSurroundingSpace_isNotAChange() {
        ConfirmedRow result = only(List.of(staged("UPI GROCER", "Groceries")), confirmed("UPI GROCER", " groceries "));

        assertThat(result.categorySource()).isEqualTo("rule");
    }

    @Test
    void aBlankCategory_countsAsOther_becauseThatIsWhatTheImportStores() {
        assertThat(only(List.of(staged("UPI GROCER", "Other")), confirmed("UPI GROCER", "")).categorySource())
                .isEqualTo("rule");
        assertThat(only(List.of(staged("UPI GROCER", null)), confirmed("UPI GROCER", "Other")).categorySource())
                .isEqualTo("rule");
    }

    @Test
    void aRowWithNoParsedCounterpart_isNotClaimedAsAReviewChoice() {
        ConfirmedRow result = only(List.of(staged("SOMETHING ELSE", "Shopping")), confirmed("UPI GROCER", "Groceries"));

        assertThat(result.categorySource()).isEqualTo("rule");
    }

    @Test
    void identicalRows_areJudgedEachAgainstTheirOwnSuggestion() {
        List<StagedRow> parsed = List.of(staged("METRO FARE", "Transport"), staged("METRO FARE", "Transport"));
        List<ConfirmedRow> confirmedRows = List.of(confirmed("METRO FARE", "Transport"), confirmed("METRO FARE", "Travel"));

        assertThat(ConfirmedRowIntegrity.withStatementFacts(parsed, confirmedRows))
                .extracting(ConfirmedRow::categorySource)
                .containsExactly("rule", CategorizationService.REVIEW_SOURCE);
    }

    @Test
    void theReviewSource_mapsToAManualDecision() {
        assertThat(CategorizationService.decisionSourceFor(CategorizationService.REVIEW_SOURCE))
                .isEqualTo(com.finora.entity.Transaction.DecisionSource.MANUAL);
    }
}
