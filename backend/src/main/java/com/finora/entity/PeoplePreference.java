package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** The user's own UPI id (V281), asked once for "Ask them": the payment link it builds pays the user. */
@Entity
@Table(name = "people_preferences")
public class PeoplePreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "own_upi_id")
    private String ownUpiId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PeoplePreference() {}

    public PeoplePreference(UUID userId, String ownUpiId) {
        this.userId = userId;
        this.ownUpiId = ownUpiId;
    }

    public UUID getUserId() { return userId; }
    public String getOwnUpiId() { return ownUpiId; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setOwnUpiId(String ownUpiId) {
        this.ownUpiId = ownUpiId;
        this.updatedAt = Instant.now();
    }
}
