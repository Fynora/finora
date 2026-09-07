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

    @Column(name = "savings_rate_score", nullable = false)
    private double savingsRateScore;

    @Column(name = "debt_score", nullable = false)
    private double debtScore;

    @Column(name = "emergency_fund_score", nullable = false)
    private double emergencyFundScore;

    @Column(name = "spend_consistency_score", nullable = false)
    private double spendConsistencyScore;

    @Column(name = "cash_flow_stability_score", nullable = false)
    private double cashFlowStabilityScore;

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
    public double getSavingsRateScore() { return savingsRateScore; }
    public void setSavingsRateScore(double savingsRateScore) { this.savingsRateScore = savingsRateScore; }
    public double getDebtScore() { return debtScore; }
    public void setDebtScore(double debtScore) { this.debtScore = debtScore; }
    public double getEmergencyFundScore() { return emergencyFundScore; }
    public void setEmergencyFundScore(double emergencyFundScore) { this.emergencyFundScore = emergencyFundScore; }
    public double getSpendConsistencyScore() { return spendConsistencyScore; }
    public void setSpendConsistencyScore(double spendConsistencyScore) { this.spendConsistencyScore = spendConsistencyScore; }
    public double getCashFlowStabilityScore() { return cashFlowStabilityScore; }
    public void setCashFlowStabilityScore(double cashFlowStabilityScore) { this.cashFlowStabilityScore = cashFlowStabilityScore; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
