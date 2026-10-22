package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The Free plan's monthly AI insights allowance (V282; spec §6): "Why?" answers used and stories
 * generated, per user per calendar month in the user's timezone -- one row per (user, month).
 * Changed only through {@code AiInsightUsageRepository}'s conditional updates, never by loading and
 * saving.
 */
@Entity
@Table(name = "ai_insight_usage")
public class AiInsightUsage {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "why_used", nullable = false)
    private int whyUsed;

    @Column(name = "story_generations", nullable = false)
    private int storyGenerations;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getMonth() { return month; }
    public int getWhyUsed() { return whyUsed; }
    public int getStoryGenerations() { return storyGenerations; }
}
