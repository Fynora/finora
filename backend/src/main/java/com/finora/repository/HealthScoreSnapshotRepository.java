package com.finora.repository;

import com.finora.entity.HealthScoreSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthScoreSnapshotRepository extends JpaRepository<HealthScoreSnapshot, UUID> {

    List<HealthScoreSnapshot> findTop6ByUserIdOrderByYearMonthDesc(UUID userId);

    Optional<HealthScoreSnapshot> findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(
            UUID userId, String yearMonth);

    /**
     * Writes this month's snapshot -- inserting it, or overwriting the figures on the row already
     * there for {@code (user_id, year_month)} -- as one atomic statement. Same shape as
     * {@code NetWorthSnapshotRepository#upsertForToday} and for the same reason: the database
     * resolves the conflict atomically, so two concurrent callers (a dashboard load racing the
     * nightly sweep, or a double-click) can never both attempt the INSERT and never raise an
     * exception either way.
     *
     * <p>{@code REQUIRES_NEW} for two reasons at once: a {@code @Modifying} query needs some active
     * transaction to run in, and -- critically -- {@code DashboardService.summarize()} (the main
     * caller) is {@code @Transactional(readOnly = true)}, under which a nested write silently
     * no-ops (read-only sets Hibernate's flush mode to MANUAL). {@code REQUIRES_NEW} keeps this
     * write in its own, genuinely writable transaction regardless of the caller's own transactional
     * state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO health_score_snapshot
               (id, user_id, year_month, overall_score, label, savings_rate_score, debt_score,
                emergency_fund_score, spend_consistency_score, cash_flow_stability_score, computed_at)
           VALUES
               (gen_random_uuid(), :userId, :yearMonth, :overallScore, :label, :savingsRateScore,
                :debtScore, :emergencyFundScore, :spendConsistencyScore, :cashFlowStabilityScore, now())
           ON CONFLICT (user_id, year_month) DO UPDATE SET
               overall_score             = EXCLUDED.overall_score,
               label                     = EXCLUDED.label,
               savings_rate_score        = EXCLUDED.savings_rate_score,
               debt_score                = EXCLUDED.debt_score,
               emergency_fund_score      = EXCLUDED.emergency_fund_score,
               spend_consistency_score   = EXCLUDED.spend_consistency_score,
               cash_flow_stability_score = EXCLUDED.cash_flow_stability_score,
               computed_at               = now()
           """, nativeQuery = true)
    void upsertForMonth(@Param("userId") UUID userId, @Param("yearMonth") String yearMonth,
                         @Param("overallScore") int overallScore, @Param("label") String label,
                         @Param("savingsRateScore") double savingsRateScore,
                         @Param("debtScore") double debtScore,
                         @Param("emergencyFundScore") double emergencyFundScore,
                         @Param("spendConsistencyScore") double spendConsistencyScore,
                         @Param("cashFlowStabilityScore") double cashFlowStabilityScore);
}
