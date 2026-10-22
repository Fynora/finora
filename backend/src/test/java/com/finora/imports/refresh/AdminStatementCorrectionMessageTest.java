package com.finora.imports.refresh;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** What the user is told after support applies a correction: counts and the account name only. */
class AdminStatementCorrectionMessageTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 31);

    @Test
    void flippedDirectionsAreSaidAsReplacedNotAsAddedAndRemoved() {
        String m = AdminStatementCorrectionService.message(0, 124, 124, 0, "Card", START, END);

        assertThat(m).contains("your Card statement (1 Mar 2026 to 31 Mar 2026).")
                .contains("124 transactions replaced with a corrected reading.")
                .doesNotContain("added").doesNotContain("removed");
    }

    @Test
    void onlyTheSurplusOnEitherSideIsSaidAsAddedOrRemoved() {
        assertThat(AdminStatementCorrectionService.message(2, 5, 3, 0, "Savings", START, END))
                .contains("2 transactions corrected, 3 transactions replaced with a corrected reading, 2 transactions added.");
        assertThat(AdminStatementCorrectionService.message(0, 1, 4, 0, "Savings", START, END))
                .contains("1 transaction replaced with a corrected reading, 3 transactions removed.");
        assertThat(AdminStatementCorrectionService.message(1, 0, 0, 0, "Savings", START, END))
                .contains("1 transaction corrected.");
    }

    @Test
    void aBalanceOnlyCorrectionSaysSo_andAMissingAccountNameOrPeriodReadsNaturally() {
        String m = AdminStatementCorrectionService.message(0, 0, 0, 2, null, null, null);

        assertThat(m).contains("applied the correction to one of your statements.")
                .contains("opening or closing balance was also updated")
                .doesNotContain("()").doesNotContain("null").doesNotContain("..");
    }

    @Test
    void itAlwaysSaysEditsAreKeptAndWhereToLook() {
        assertThat(AdminStatementCorrectionService.message(1, 0, 0, 0, "Savings", START, END))
                .contains("Any transaction you edited keeps your changes.")
                .endsWith("Open Statements in Fynora to review it.");
    }
}
