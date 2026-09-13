package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One turn in a {@link ChatConversation} (Phase 4). See docs/superpowers/specs/
 * 2026-09-13-fino-ai-implementation-plan.md §4.6 for feedback -- nullable, only ever set on an
 * ASSISTANT-role row by a user's thumbs up/down tap; not constrained in SQL (see V202's comment),
 * enforced at the service layer instead.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_SYSTEM = "SYSTEM";
    public static final String ROLE_TOOL = "TOOL";

    public static final String FEEDBACK_HELPFUL = "HELPFUL";
    public static final String FEEDBACK_NOT_HELPFUL = "NOT_HELPFUL";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls_json", columnDefinition = "jsonb")
    private Map<String, Object> toolCallsJson;

    @Column(length = 20)
    private String feedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Map<String, Object> getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(Map<String, Object> toolCallsJson) { this.toolCallsJson = toolCallsJson; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public Instant getCreatedAt() { return createdAt; }
}
