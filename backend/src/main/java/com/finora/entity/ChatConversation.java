package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A Fyn chat thread (Phase 4). See docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md,
 * §6 Phase 1 -- schema shipped ahead of Phase 4 so the whole chat schema lands once.
 */
@Entity
@Table(name = "chat_conversations")
public class ChatConversation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
