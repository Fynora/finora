package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One AI text attempt for the AI insights foundation (V282; docs/superpowers/specs/
 * 2026-10-22-ai-insights-foundation-design.md §5.4): the month story or a "Why?" answer, with the
 * fact sheet it was written from. Rows are created by {@code AiInsightTextRepository#insertPending}
 * (the single-flight insert), never by {@code save} of a new entity, so the id is assigned there.
 */
@Entity
@Table(name = "ai_insight_texts")
public class AiInsightText {

    public static final String KIND_STORY = "STORY";
    public static final String KIND_WHY = "WHY";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "facts_hash", nullable = false, length = 64)
    private String factsHash;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;

    @Column(nullable = false, length = 10)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> sheet;

    @Column(columnDefinition = "text")
    private String text;

    // A JSON array of row keys, held as its JSON text: mapping a List<String> as JSON here made
    // Hibernate resolve CategoryRule's List<String> payee_aliases (a Postgres TEXT[]) as JSON too, and
    // schema validation then failed at startup.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_order", columnDefinition = "jsonb")
    private String rowOrderJson;

    @Column(name = "reason_code", length = 32)
    private String reasonCode;

    @Column(name = "plan_code", length = 20)
    private String planCode;

    @Column(name = "regenerated_for_version", nullable = false)
    private boolean regeneratedForVersion;

    @Column(length = 16)
    private String feedback;

    @Column(name = "feedback_reason", length = 16)
    private String feedbackReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getKind() { return kind; }
    public String getSubject() { return subject; }
    public String getMonth() { return month; }
    public String getFactsHash() { return factsHash; }
    public String getPromptVersion() { return promptVersion; }
    public String getModel() { return model; }
    public String getPolicyVersion() { return policyVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getSheet() { return sheet; }
    public void setSheet(Map<String, Object> sheet) { this.sheet = sheet; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getRowOrderJson() { return rowOrderJson; }
    public void setRowOrderJson(String rowOrderJson) { this.rowOrderJson = rowOrderJson; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getPlanCode() { return planCode; }
    public boolean isRegeneratedForVersion() { return regeneratedForVersion; }
    public void setRegeneratedForVersion(boolean regeneratedForVersion) { this.regeneratedForVersion = regeneratedForVersion; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getFeedbackReason() { return feedbackReason; }
    public void setFeedbackReason(String feedbackReason) { this.feedbackReason = feedbackReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
