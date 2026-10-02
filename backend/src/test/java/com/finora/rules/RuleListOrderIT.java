package com.finora.rules;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.PagedResponse;
import com.finora.dto.AdminDtos.SearchResultDto;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.AdminSearchService;
import com.finora.service.RuleEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule management lists (admin Global Rules page, a user's rule list, admin search) must show
 * every rule once, in a total order: priority, then comparison value, then id, with a user's own
 * rules ahead of the global ones as RuleEngineService evaluates them. They used to order by
 * priority alone. Every
 * V19 seed rule has priority 100, so ties came back in whatever order Postgres produced -- once the
 * table has statistics that is heap order, and recordMatch() rewrites a matched row at the end of
 * the heap, so a rule could move from one page to another between two page fetches. These tests
 * ANALYZE first for the same reason GlobalRuleTieOrderIT does: before statistics the planner reads
 * the (scope, enabled) index, whose order an update does not move.
 */
class RuleListOrderIT extends AbstractIntegrationTest {

    private static final String LIST_ORDER = " ORDER BY priority, comparison_value, id";

    @Autowired private RuleService ruleService;
    @Autowired private RuleEngineService ruleEngineService;
    @Autowired private AdminSearchService adminSearchService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> createdRules = new ArrayList<>();
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdRules.forEach(id -> jdbc.update("DELETE FROM category_rules WHERE id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    private void analyze() {
        jdbc.execute("ANALYZE category_rules");
    }

    private UUID globalRule(String contains, int priority) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value,
                    action_type, action_value, priority, enabled, created_at, updated_at)
                VALUES (?, NULL, 'GLOBAL', 'DESCRIPTION', 'CONTAINS', ?, 'ASSIGN_CATEGORY', 'Shopping', ?, true,
                    TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, id, contains, priority);
        createdRules.add(id);
        return id;
    }

    private UUID userRule(UUID userId, String contains, int priority) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value,
                    action_type, action_value, priority, enabled, created_at, updated_at)
                VALUES (?, ?, 'USER', 'DESCRIPTION', 'CONTAINS', ?, 'ASSIGN_CATEGORY', 'Shopping', ?, true,
                    TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                """, id, userId, contains, priority);
        createdRules.add(id);
        return id;
    }

    private UUID createUser() {
        User user = new User();
        user.setEmail("rule-list-order-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Rule List Order IT User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        UUID id = userRepository.save(user).getId();
        createdUsers.add(id);
        return id;
    }

    private List<UUID> globalIdsInListOrder() {
        return jdbc.queryForList("SELECT id FROM category_rules WHERE scope = 'GLOBAL'" + LIST_ORDER, UUID.class);
    }

    @Test
    void pagingThroughGlobalRulesShowsEachRuleExactlyOnceWhileMatchesAreRecorded() {
        List<UUID> expected = globalIdsInListOrder();
        assertThat(expected).as("V19 seeds GLOBAL rules").hasSizeGreaterThan(20);
        analyze();

        int size = 10;
        List<UUID> listed = new ArrayList<>();
        int totalPages = Integer.MAX_VALUE;
        for (int page = 0; page < totalPages; page++) {
            PagedResponse<RuleDto> response = ruleService.listGlobal(page, size);
            totalPages = response.totalPages();
            response.content().forEach(r -> listed.add(r.id()));
            // Imports confirming while an admin pages through the list: each confirm UPDATEs the
            // rule that matched, moving its row.
            response.content().forEach(r -> ruleEngineService.recordMatch(r.id()));
        }

        Map<UUID, Integer> timesListed = new HashMap<>();
        listed.forEach(id -> timesListed.merge(id, 1, Integer::sum));
        List<UUID> repeated = timesListed.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        List<UUID> missing = expected.stream().filter(id -> !timesListed.containsKey(id)).toList();

        assertThat(repeated).as("rules listed on more than one page").isEmpty();
        assertThat(missing).as("rules never listed on any page").isEmpty();
        assertThat(listed).as("pages concatenated, in list order").containsExactlyElementsOf(expected);
    }

    @Test
    void tiedGlobalRulesAreListedByComparisonValueThenId() {
        // Inserted out of alphabetical order, all at one priority, as V19's rows are.
        UUID charlie = globalRule("listprobecharlie", 7);
        UUID alpha = globalRule("listprobealpha", 7);
        UUID bravo = globalRule("listprobebravo", 7);
        analyze();
        ruleEngineService.recordMatch(alpha);

        List<UUID> listed = new ArrayList<>();
        PagedResponse<RuleDto> first = ruleService.listGlobal(0, 100);
        first.content().forEach(r -> listed.add(r.id()));
        for (int page = 1; page < first.totalPages(); page++) {
            ruleService.listGlobal(page, 100).content().forEach(r -> listed.add(r.id()));
        }

        assertThat(listed.stream().filter(List.of(alpha, bravo, charlie)::contains).toList())
                .containsExactly(alpha, bravo, charlie);
    }

    @Test
    void aUsersRuleListShowsTheirOwnRulesFirstBecauseEvaluationRunsThemFirst() {
        UUID userId = createUser();
        // A personal rule at a later priority than every global rule still runs before all of them
        // (RuleEngineService evaluates USER rules in full before any GLOBAL rule).
        UUID late = userRule(userId, "userprobezulu", 500);
        UUID early = userRule(userId, "userprobeyankee", 1);
        UUID tieB = userRule(userId, "userprobebravo", 100);
        UUID tieA = userRule(userId, "userprobealpha", 100);
        analyze();
        ruleEngineService.recordMatch(tieA);

        List<UUID> expected = new ArrayList<>(List.of(early, tieA, tieB, late));
        expected.addAll(globalIdsInListOrder());

        List<UUID> listed = ruleService.listForUser(userId).stream().map(RuleDto::id).toList();

        assertThat(listed).containsExactlyElementsOf(expected);
    }

    @Test
    void adminSearchShowsTheSameFirstFiveGlobalRulesEveryTime() {
        // Six hits for a limit of five: the five shown must not depend on which rows were updated last.
        List<UUID> rules = new ArrayList<>();
        for (String suffix : List.of("foxtrot", "echo", "delta", "charlie", "bravo", "alpha")) {
            rules.add(globalRule("srchprobe" + suffix, 100));
        }
        analyze();
        // Move the alphabetically first rows to the end of the heap.
        ruleEngineService.recordMatch(rules.get(5));
        ruleEngineService.recordMatch(rules.get(4));

        List<String> shown = adminSearchService.search("srchprobe").stream()
                .filter(r -> "rule".equals(r.type()))
                .map(SearchResultDto::id)
                .toList();

        assertThat(shown).containsExactly(
                rules.get(5).toString(), rules.get(4).toString(), rules.get(3).toString(),
                rules.get(2).toString(), rules.get(1).toString());
    }

}
