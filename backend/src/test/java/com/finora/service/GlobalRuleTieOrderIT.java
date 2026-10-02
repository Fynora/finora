package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.CategoryRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two GLOBAL rules at the same priority that both match one description must pick the same winner
 * on every load. Before the fix, ruleSet() ordered GLOBAL rules by priority alone; every seeded
 * rule shares priority 100, so ties came back in whatever order Postgres produced. Once the table
 * has statistics (autovacuum's ANALYZE) the plan is a Seq Scan + Sort, so ties come back in heap
 * order -- and recordMatch() UPDATEs the winning row on every confirm, writing a new tuple version
 * at the end of the heap. The rule matched most recently then lost the next tie, so one narration
 * holding both 'zepto' and 'airtel' flipped between Groceries and Utilities from one import to the
 * next. Before ANALYZE the planner picks the (scope, enabled) index, whose order an update does not
 * move -- which is why a fresh database hides the flip, and why these tests ANALYZE first.
 *
 * <p>Fixed by ordering ties by comparison value, then id (CategoryRuleRepository).
 */
class GlobalRuleTieOrderIT extends AbstractIntegrationTest {

    @Autowired private RuleEngineService ruleEngineService;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeRules() {
        created.forEach(id -> jdbc.update("DELETE FROM category_rules WHERE id = ?", id));
    }

    /** Gives the planner real row counts, as autovacuum does on any database that has run a while. */
    private void analyze() {
        jdbc.execute("ANALYZE category_rules");
    }

    private UUID globalRule(String contains, String category) {
        UUID id = UUID.randomUUID();
        // Same created_at for both, as in V19, where every seeded row shares one transaction's now().
        jdbc.update("""
                INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value,
                    action_type, action_value, priority, enabled, created_at, updated_at)
                VALUES (?, NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', ?, 'ASSIGN_CATEGORY', ?, 100, true,
                    TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, id, contains, category);
        created.add(id);
        return id;
    }

    private String winner(String description) {
        return ruleEngineService.evaluateCategoryRule(
                        ruleEngineService.ruleSet(UUID.randomUUID()), description, null, null, null)
                .map(m -> m.rule().getActionValue())
                .orElse(null);
    }

    @Test
    void equalPriorityGlobalTieKeepsTheSameWinnerAcrossLoadsAndMatchCounts() {
        UUID first = globalRule("tieprobealpha", "Groceries");
        UUID second = globalRule("tieprobebeta", "Utilities");
        String description = "UPI/tieprobealpha/tieprobebeta/REF";
        analyze();

        List<String> winners = new ArrayList<>();
        for (int run = 0; run < 6; run++) {
            String won = winner(description);
            winners.add(won);
            // What confirm does with the winning rule (CategorizationService -> recordMatch): an
            // UPDATE, so the row's physical position changes between runs.
            ruleEngineService.recordMatch("Groceries".equals(won) ? first : second);
        }

        // Ties go to the comparison value that sorts first: 'tieprobealpha' before 'tieprobebeta'.
        assertThat(winners).as("winner on each of 6 consecutive loads").containsOnly("Groceries");
    }

    @Test
    void ruleOrderDoesNotDependOnWhichRowWasUpdatedLast() {
        UUID first = globalRule("tieprobegamma", "Groceries");
        UUID second = globalRule("tieprobedelta", "Utilities");
        String description = "UPI/tieprobegamma/tieprobedelta/REF";
        analyze();

        ruleEngineService.recordMatch(first);
        String afterFirstUpdated = winner(description);
        ruleEngineService.recordMatch(second);
        String afterSecondUpdated = winner(description);

        assertThat(afterFirstUpdated).isEqualTo("Utilities"); // 'tieprobedelta' sorts before 'tieprobegamma'
        assertThat(afterSecondUpdated).isEqualTo(afterFirstUpdated);
    }
}
