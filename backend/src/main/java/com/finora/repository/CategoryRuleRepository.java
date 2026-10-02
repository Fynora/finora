package com.finora.repository;

import com.finora.entity.CategoryRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    // Used by RuleEngineService -- enabled-only, scoped to one user's own rules, in evaluation
    // order. Rules at the same priority are ordered by comparison value, then id: see the GLOBAL
    // query just below for why priority alone is not an order.
    List<CategoryRule> findByUserIdAndEnabledTrueOrderByPriorityAscComparisonValueAscIdAsc(UUID userId);

    // Used by RuleEngineService -- enabled-only global rules, in evaluation order. First match
    // wins, so ties need a fixed order: every rule V19 seeds has priority 100, and ordering by
    // priority alone left them in whatever order Postgres returned. Once the table has statistics
    // that is heap order, and recordMatch() rewrites the matched row at the end of the heap -- so
    // the rule matched last lost the next tie, and one narration matching 'zepto' and 'airtel'
    // flipped between Groceries and Utilities from one import to the next (GlobalRuleTieOrderIT).
    // Comparison value, not created_at or id alone: seeded rows share one created_at, and ids are
    // random per database, so either would pick a different winner in prod than in dev.
    List<CategoryRule> findByScopeAndEnabledTrueOrderByPriorityAscComparisonValueAscIdAsc(CategoryRule.Scope scope);

    // The management lists below (RuleService, AdminSearchService) order by priority, then
    // comparison value, then id -- a total order, and the tie order evaluation is meant to use.
    // Priority alone is not an order: every V19 seed rule has priority 100, and with ties left to
    // Postgres, paging the 46 seeded rules ten at a time listed 45 distinct rules, and 26 when
    // matches were recorded between page fetches, since recordMatch() moves the matched row
    // (measured 2026-10-02 against a freshly migrated, ANALYZEd database; see RuleListOrderIT).

    // Used by RuleService.listForUser -- this user's own rules, regardless of enabled state (so a
    // disabled rule is still listed, just not evaluated). GLOBAL rows have user_id NULL, so none
    // are included; RuleService appends them after, as RuleEngineService evaluates them after.
    List<CategoryRule> findByUserIdOrderByPriorityAscComparisonValueAscIdAsc(UUID userId);

    // Used by RuleService.listForUser and AdminSearchService, which need the FULL global-rule set
    // -- not paginated, unlike the admin Global Rules page's own overload just below.
    List<CategoryRule> findByScopeOrderByPriorityAscComparisonValueAscIdAsc(CategoryRule.Scope scope);

    // Backs the admin Global Rules page (AdminRuleController), paginated -- every GLOBAL rule
    // regardless of enabled state, same "management view sees everything, evaluation view sees
    // enabled-only" split findByScopeAndEnabledTrueOrderByPriorityAscComparisonValueAscIdAsc above
    // already establishes.
    // A Pageable overload of the same derived query just above -- Spring Data lets both coexist,
    // so AdminSearchService keeps its full unpaged list while this page gets real pagination.
    Page<CategoryRule> findByScopeOrderByPriorityAscComparisonValueAscIdAsc(CategoryRule.Scope scope, Pageable pageable);

    /**
     * Financial Intelligence Workspace, Rule Management module -- bulk UPDATE rather than
     * find-increment-save, both because a GLOBAL rule's row is shared across every user (so
     * "increment what's currently loaded" would race under any real concurrency) and because the
     * callers (RuleEngineService.recordMatch(), invoked from CategorizationService at actual
     * transaction-write time -- see that class's doc comment for why it's NOT called from
     * suggest()/evaluate*() directly) already have everything they need (the ruleId) without
     * loading the entity first.
     */
    @Modifying
    @Query("UPDATE CategoryRule r SET r.matchCount = r.matchCount + 1, r.lastMatchedAt = :now WHERE r.id = :ruleId")
    void recordMatch(@Param("ruleId") UUID ruleId, @Param("now") Instant now);

    /** AccountPurgeSweepService -- only ever matches this user's own scope='USER' rows;
     *  scope='GLOBAL' rows always have user_id IS NULL (chk_category_rules_scope_user) and are
     *  never touched. Hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);

    /** DataExportService -- every rule this user owns, any enabled state. Safe by construction,
     *  the same guarantee deleteByUserId above already relies on: scope='GLOBAL' rows always have
     *  user_id IS NULL, so this can never pull in a shared rule that isn't the caller's own. */
    List<CategoryRule> findByUserId(UUID userId);

    /** Custom-category rename/delete cascade -- every USER-scope ASSIGN_CATEGORY/MARK_INVESTMENT
     *  rule whose action_value still names the category being renamed or deleted, so it can be
     *  rewritten in lockstep. GLOBAL rules are never matched here (this repo's scope='USER' rows
     *  never include a GLOBAL row -- see this interface's other USER-scoped methods for the same
     *  invariant), which is correct: global rules only ever reference immutable system category
     *  names, so they never need this cascade. */
    List<CategoryRule> findByUserIdAndActionTypeInAndActionValueIgnoreCase(
            UUID userId, List<CategoryRule.ActionType> actionTypes, String actionValue);

    /** The user's saved answers to the recurring-payment question: their USER-scope PAYEE rules. */
    @Query("SELECT r FROM CategoryRule r WHERE r.userId = :userId"
            + " AND r.scope = com.finora.entity.CategoryRule.Scope.USER"
            + " AND r.field = com.finora.entity.CategoryRule.Field.PAYEE")
    List<CategoryRule> findUserPayeeRules(@Param("userId") UUID userId);

    /** The user's saved answer for one payee, matched ignoring case like uq_category_rules_user_payee. */
    @Query("SELECT r FROM CategoryRule r WHERE r.userId = :userId"
            + " AND r.scope = com.finora.entity.CategoryRule.Scope.USER"
            + " AND r.field = com.finora.entity.CategoryRule.Field.PAYEE"
            + " AND lower(r.comparisonValue) = lower(:label)")
    Optional<CategoryRule> findUserPayeeRule(@Param("userId") UUID userId, @Param("label") String label);

    /**
     * Serialises one user's recurring-payment answers until the calling transaction ends (a Postgres
     * transaction-scoped advisory lock on {@code key}). Two answers at once -- a double tap, two open
     * tabs -- would otherwise race: on the same payee both re-file the same transactions and the
     * second fails on their versions; on two payees both may create the same new category and the
     * second fails on its unique name. Taken first, before anything is read, so the second answer
     * reads what the first committed.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
            nativeQuery = true)
    int lockAnswers(@Param("key") String key);

    /**
     * Inserts a payee rule unless the user already has one for this payee (uq_category_rules_user_payee),
     * in which case nothing happens. Two concurrent answers for the same payee therefore leave one
     * rule; the caller loads it with {@link #findUserPayeeRule} and updates it either way.
     *
     * @return 1 when a row was inserted, 0 when one already existed
     */
    @Modifying
    @Query(value = "INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value,"
            + " action_type, action_value, amount_min, amount_max, priority)"
            + " VALUES (:id, :userId, 'USER', 'PAYEE', 'EQUALS', :label, 'ASSIGN_CATEGORY', :category, :min, :max,"
            + " :priority)"
            + " ON CONFLICT (user_id, lower(comparison_value)) WHERE field = 'PAYEE' AND scope = 'USER' DO NOTHING",
            nativeQuery = true)
    int insertPayeeRuleIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("label") String label,
                                @Param("category") String category, @Param("min") BigDecimal min,
                                @Param("max") BigDecimal max, @Param("priority") int priority);
}
