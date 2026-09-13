package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row per Fyn LLM call, across every surface (Phase 2 import-diagnosis assist, Phase 3
 * insights narration, Phase 4 chat) -- see docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md,
 * §4.3. Deliberately separate from {@link AuditLog}: cost/token/latency aggregation for
 * governance (§4.4) needs real typed columns, not a JSONB blob. No redaction-sweep column
 * (contrast {@link AuditLog#getRedactedAt()}) -- by design (§3/§4.2), toolInputs/toolOutputs here
 * never carry raw transaction/account data, only Tier 0/1 aggregate facts.
 */
@Entity
@Table(name = "ai_audit_log")
public class AiAuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_inputs", columnDefinition = "jsonb")
    private Map<String, Object> toolInputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_outputs", columnDefinition = "jsonb")
    private Map<String, Object> toolOutputs;

    @Column(name = "tokens_in", nullable = false)
    private int tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private int tokensOut;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal cost;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public Map<String, Object> getToolInputs() { return toolInputs; }
    public void setToolInputs(Map<String, Object> toolInputs) { this.toolInputs = toolInputs; }
    public Map<String, Object> getToolOutputs() { return toolOutputs; }
    public void setToolOutputs(Map<String, Object> toolOutputs) { this.toolOutputs = toolOutputs; }
    public int getTokensIn() { return tokensIn; }
    public void setTokensIn(int tokensIn) { this.tokensIn = tokensIn; }
    public int getTokensOut() { return tokensOut; }
    public void setTokensOut(int tokensOut) { this.tokensOut = tokensOut; }
    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal cost) { this.cost = cost; }
    public int getLatencyMs() { return latencyMs; }
    public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
}
