package com.finora.imports.refresh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The line between a correction a refresh may offer and a removal it holds for an admin. */
class StatementRefreshHoldThresholdTest {

    @Test
    void oneOrTwoRemovedRows_areNeverHeld_evenOnATinyStatement() {
        assertThat(StatementRefreshDryRunService.removesTooMuch(1, 1)).isFalse();
        assertThat(StatementRefreshDryRunService.removesTooMuch(2, 2)).isFalse();
    }

    @Test
    void exactlyAQuarter_isNotHeld_andOneRowPastIt_is() {
        assertThat(StatementRefreshDryRunService.removesTooMuch(3, 12)).isFalse();
        assertThat(StatementRefreshDryRunService.removesTooMuch(3, 11)).isTrue();
    }

    @Test
    void noRemovals_areNeverHeld() {
        assertThat(StatementRefreshDryRunService.removesTooMuch(0, 0)).isFalse();
        assertThat(StatementRefreshDryRunService.removesTooMuch(0, 500)).isFalse();
    }
}
