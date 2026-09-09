package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A user-dismissed recurring group, keyed on the same {@code merchant} string
 * RecurringService.detectForUser groups by -- there is no other stable identity for a detected
 * group, since it is recomputed fresh on every call rather than being a persisted entity itself.
 */
@Entity
@Table(name = "recurring_dismissals")
public class RecurringDismissal {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "merchant", nullable = false)
    private String merchant;

    @Column(name = "dismissed_at", nullable = false)
    private Instant dismissedAt = Instant.now();

    public RecurringDismissal() {}

    public RecurringDismissal(UUID userId, String merchant) {
        this.userId = userId;
        this.merchant = merchant;
    }

    public UUID getUserId() { return userId; }
    public String getMerchant() { return merchant; }
    public Instant getDismissedAt() { return dismissedAt; }
}
