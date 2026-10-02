package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single evaluable rule -- either GLOBAL (system-seeded, read-only to users, user_id null) or
 * USER (authored by one user, user_id set). See docs/rule-engine-relationship-engine-eds.md §3.1
 * for the full design. Not extending BaseEntity: rules aren't soft-deleted or optimistically
 * locked today (same reasoning as Merchant/MerchantAlias -- see BaseEntity's own class comment on
 * which entities actually need those columns).
 */
@Entity
@Table(name = "category_rules")
public class CategoryRule {

    public enum Scope { GLOBAL, USER }

    // DESCRIPTION/MERCHANT are string fields (matched via the operator against comparisonValue);
    // AMOUNT is numeric (GT/LT/BETWEEN make sense here, CONTAINS/STARTS_WITH do not);
    // ACCOUNT_TYPE matches Account.Type's name() (SAVINGS/CREDIT_CARD/...).
    // PAYEE is the payee label (CategoryRules.extractMerchantLabel) and matches money going out
    // only -- see RuleEngineService.matches. Created by the recurring-payment question (V248).
    public enum Field { DESCRIPTION, AMOUNT, MERCHANT, ACCOUNT_TYPE, PAYEE }

    public enum Operator { CONTAINS, EQUALS, STARTS_WITH, GT, LT, BETWEEN }

    // Only ASSIGN_CATEGORY is wired into CategorizationService.suggest() as of this milestone
    // (see EDS §6 Non-goals) -- the others are recognized and persisted so rules can be authored
    // and evaluated (RuleEngineService.evaluate() returns a match regardless of action type), but
    // applying MARK_TRANSFER/MARK_INVESTMENT/MARK_SUBSCRIPTION/ADD_TAG to the resulting
    // Transaction is a fast-follow left for CsvImportService/TransactionService to call
    // explicitly once that wiring is built.
    public enum ActionType { ASSIGN_CATEGORY, MARK_TRANSFER, MARK_INVESTMENT, MARK_SUBSCRIPTION, ADD_TAG }

    @Id
    @GeneratedValue
    private UUID id;

    // Null for GLOBAL scope -- enforced by chk_category_rules_scope_user in V17.
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Field field;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Operator operator;

    @Column(name = "comparison_value", nullable = false, columnDefinition = "TEXT")
    private String comparisonValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ActionType actionType;

    @Column(name = "action_value", columnDefinition = "TEXT")
    private String actionValue;

    // Lower runs first. USER rules are evaluated as a group before GLOBAL rules regardless of
    // this value (see RuleEngineService) -- priority only orders rules within the same scope.
    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Financial Intelligence Workspace, Rule Management module -- execution telemetry, written
    // via CategoryRuleRepository.recordMatch()'s bulk UPDATE (not through this entity's setters
    // in the normal write path, to avoid a read-modify-write race on a value updated from every
    // matching transaction write) rather than by loading, incrementing, and re-saving this whole
    // entity. Setters below exist for tests and any future direct-entity path only.
    @Column(name = "match_count", nullable = false)
    private long matchCount = 0;

    @Column(name = "last_matched_at")
    private Instant lastMatchedAt;

    // Optional bounds on the transaction amount, inclusive, checked in addition to the rule's own
    // condition (V248). Null on both sides means no bound, which every rule before V248 has.
    @Column(name = "amount_min")
    private BigDecimal amountMin;

    @Column(name = "amount_max")
    private BigDecimal amountMax;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Scope getScope() { return scope; }
    public void setScope(Scope scope) { this.scope = scope; }
    public Field getField() { return field; }
    public void setField(Field field) { this.field = field; }
    public Operator getOperator() { return operator; }
    public void setOperator(Operator operator) { this.operator = operator; }
    public String getComparisonValue() { return comparisonValue; }
    public void setComparisonValue(String comparisonValue) { this.comparisonValue = comparisonValue; }
    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }
    public String getActionValue() { return actionValue; }
    public void setActionValue(String actionValue) { this.actionValue = actionValue; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getMatchCount() { return matchCount; }
    public void setMatchCount(long matchCount) { this.matchCount = matchCount; }
    public Instant getLastMatchedAt() { return lastMatchedAt; }
    public void setLastMatchedAt(Instant lastMatchedAt) { this.lastMatchedAt = lastMatchedAt; }
    public BigDecimal getAmountMin() { return amountMin; }
    public void setAmountMin(BigDecimal amountMin) { this.amountMin = amountMin; }
    public BigDecimal getAmountMax() { return amountMax; }
    public void setAmountMax(BigDecimal amountMax) { this.amountMax = amountMax; }
}
