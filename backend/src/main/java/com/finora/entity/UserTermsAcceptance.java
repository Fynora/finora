package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One acceptance of the Terms of Service and Privacy Policy (V302). Append-only: a new acceptance is
 * a new row, never an update, so the history shows every version a user agreed to and how.
 */
@Entity
@Table(name = "user_terms_acceptances")
public class UserTermsAcceptance {

    /** The user ticked the required box. */
    public static final String METHOD_CHECKBOX = "CHECKBOX";
    /** The passive "by continuing you agree" notice V223 recorded (backfilled rows only). */
    public static final String METHOD_NOTICE = "NOTICE";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "terms_version", nullable = false, updatable = false, length = 32)
    private String termsVersion;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    /** A {@link ClientPlatform} name, or UNKNOWN for a backfilled row. Advisory (X-Client-Platform). */
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private String channel;

    @Column(name = "method", nullable = false, updatable = false, length = 16)
    private String method;

    protected UserTermsAcceptance() {}

    public UserTermsAcceptance(UUID userId, String termsVersion, Instant acceptedAt, ClientPlatform channel) {
        this.userId = userId;
        this.termsVersion = termsVersion;
        this.acceptedAt = acceptedAt;
        this.channel = channel.name();
        this.method = METHOD_CHECKBOX;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTermsVersion() { return termsVersion; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public String getChannel() { return channel; }
    public String getMethod() { return method; }
}
