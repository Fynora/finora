package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "health_score_snapshot")
public class HealthScoreSnapshot {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "year_month", nullable = false)
    private String yearMonth;

    @Column(name = "overall_score", nullable = false)
    private int overallScore;

    @Column(nullable = false)
    private String label;

    /** Null when this part could not be measured yet (V296). */
    @Column(name = "savings_rate_score")
    private Double savingsRateScore;

    @Column(name = "debt_score", nullable = false)
    private double debtScore;

    /** Null when this part could not be measured yet (V296). */
    @Column(name = "emergency_fund_score")
    private Double emergencyFundScore;

    /** Null when this part could not be measured yet (V296). */
    @Column(name = "spend_consistency_score")
    private Double spendConsistencyScore;

    /** Null when this part could not be measured yet (V296). */
    @Column(name = "cash_flow_stability_score")
    private Double cashFlowStabilityScore;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getYearMonth() { return yearMonth; }
    public void setYearMonth(String yearMonth) { this.yearMonth = yearMonth; }
    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int overallScore) { this.overallScore = overallScore; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Double getSavingsRateScore() { return savingsRateScore; }
    public void setSavingsRateScore(Double savingsRateScore) { this.savingsRateScore = savingsRateScore; }
    public double getDebtScore() { return debtScore; }
    public void setDebtScore(double debtScore) { this.debtScore = debtScore; }
    public Double getEmergencyFundScore() { return emergencyFundScore; }
    public void setEmergencyFundScore(Double emergencyFundScore) { this.emergencyFundScore = emergencyFundScore; }
    public Double getSpendConsistencyScore() { return spendConsistencyScore; }
    public void setSpendConsistencyScore(Double spendConsistencyScore) { this.spendConsistencyScore = spendConsistencyScore; }
    public Double getCashFlowStabilityScore() { return cashFlowStabilityScore; }
    public void setCashFlowStabilityScore(Double cashFlowStabilityScore) { this.cashFlowStabilityScore = cashFlowStabilityScore; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
