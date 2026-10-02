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

    // Used by RuleEngineService -- enabled-only, priority order, scoped to one user's own rules.
    List<CategoryRule> findByUserIdAndEnabledTrueOrderByPriorityAsc(UUID userId);

    // Used by RuleEngineService -- enabled-only global rules, priority order.
    List<CategoryRule> findByScopeAndEnabledTrueOrderByPriorityAsc(CategoryRule.Scope scope);

    // Used by RuleService for the management API -- every rule a user can see: their own plus
    // the read-only global set, regardless of enabled state (so a disabled rule is still listed,
    // just not evaluated).
    List<CategoryRule> findByUserIdOrScopeOrderByPriorityAsc(UUID userId, CategoryRule.Scope scope);

    // Used by AdminSearchService, which needs the FULL global-rule set to search across -- not
    // paginated, unlike the admin Global Rules page's own overload just below.
    List<CategoryRule> findByScopeOrderByPriorityAsc(CategoryRule.Scope scope);

    // Backs the admin Global Rules page (AdminRuleController), paginated -- every GLOBAL rule
    // regardless of enabled state, same "management view sees everything, evaluation view sees
    // enabled-only" split findByScopeAndEnabledTrueOrderByPriorityAsc above already establishes.
    // A Pageable overload of the same derived query just above -- Spring Data lets both coexist,
    // so AdminSearchService keeps its full unpaged list while this page gets real pagination.
    Page<CategoryRule> findByScopeOrderByPriorityAsc(CategoryRule.Scope scope, Pageable pageable);

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
     * Inserts a payee rule unless the user already has one for this payee (uq_category_rules_user_payee),
     * in which case nothing happens. Two concurrent answers for the same payee therefore leave one
     * rule; the caller loads it with {@link #findUserPayeeRule} and updates it either way.
     *
     * @return 1 when a row was inserted, 0 when one already existed
     */
    /**
     * Serialises answers for one user and payee until the calling transaction ends (a Postgres
     * transaction-scoped advisory lock). Two answers at once -- a double tap, two open tabs -- both
     * re-file the same transactions; without this the second fails on their versions. Taken first,
     * before anything is read, so the second answer reads what the first committed.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
            nativeQuery = true)
    int lockPayeeAnswer(@Param("key") String key);

    @Modifying
    @Query(value = "INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value,"
            + " action_type, action_value, amount_min, amount_max)"
            + " VALUES (:id, :userId, 'USER', 'PAYEE', 'EQUALS', :label, 'ASSIGN_CATEGORY', :category, :min, :max)"
            + " ON CONFLICT (user_id, lower(comparison_value)) WHERE field = 'PAYEE' AND scope = 'USER' DO NOTHING",
            nativeQuery = true)
    int insertPayeeRuleIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("label") String label,
                                @Param("category") String category, @Param("min") BigDecimal min,
                                @Param("max") BigDecimal max);
}
