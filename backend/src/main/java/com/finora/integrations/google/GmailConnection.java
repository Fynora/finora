package com.finora.integrations.google;

import com.finora.security.crypto.EncryptedValue;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One user's link to one Gmail mailbox — Phase B of the Gmail Transaction Sync design
 * (docs/proposals/gmail-transaction-sync-proposal.md). Connection lifecycle only: nothing here
 * syncs, parses, or ingests anything.
 *
 * <p><b>Identity is {@link #googleUserId}, not the email address.</b> Google's `sub` is stable for
 * the life of the account; an email is not (account renames, Workspace domain migrations). Keying
 * off email would silently create a second connection for the same mailbox the day someone renames
 * theirs. The email is stored so the user can see which mailbox is connected, and for nothing else.
 *
 * <p><b>The refresh token is never held in plaintext.</b> {@link #encryptedRefreshToken} +
 * {@link #encryptionKeyId} are exactly the two halves of {@link EncryptedValue} (ADR-009), and
 * {@link #credential()} reassembles them for {@code EncryptionService.decrypt}. There is
 * deliberately no getter returning a decrypted value — decryption requires the service, which keeps
 * "who can read this" a question about wiring rather than about field visibility.
 */
@Entity
@Table(name = "gmail_connections")
public class GmailConnection {

    /**
     * Where a connection is in its life.
     *
     * <p>{@link #LIVE} is the set the unique indexes in V80 are scoped to — one live connection per
     * user, and one per Google account. A disconnected or revoked row keeps its audit trail without
     * blocking a fresh connection.
     */
    public enum Status {
        /** Usable: a refresh token is present and Google has not rejected it. */
        CONNECTED,
        /** Google rejected the token — password change, user revoked access, suspicious-activity
         *  lock. Needs the user to reconnect; retrying cannot fix it. */
        REAUTH_REQUIRED,
        /** The user disconnected from inside Finora. */
        DISCONNECTED,
        /** Revoked at Google's end and detected by us. */
        REVOKED;

        /** Statuses that occupy a user's (and a mailbox's) single connection slot. */
        public static final Set<Status> LIVE = EnumSet.of(CONNECTED, REAUTH_REQUIRED);

        /** Statuses that involuntarily broke the connection -- the user needs to reconnect, and
         *  nothing else will fix it. Deliberately excludes DISCONNECTED: that one was the user's
         *  own choice, and looking exactly like "never connected" again is correct for it, not a
         *  gap to fix. The one caller that used to duplicate this test as raw string literals
         *  (Settings.tsx) now reads {@link GmailConnectionStatusDto#needsReconnect} instead, so a
         *  status added here is automatically correct on the frontend with no matching edit. */
        private static final Set<Status> NEEDS_RECONNECT = EnumSet.of(REAUTH_REQUIRED, REVOKED);

        public boolean isLive() { return LIVE.contains(this); }

        public boolean needsReconnect() { return NEEDS_RECONNECT.contains(this); }
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "google_user_id", nullable = false)
    private String googleUserId;

    @Column(name = "google_email", nullable = false)
    private String googleEmail;

    /** Base64 AES-256-GCM ciphertext. Null once disconnected — the row survives for its audit
     *  trail, the credential does not. */
    @Column(name = "encrypted_refresh_token")
    private String encryptedRefreshToken;

    @Column(name = "encryption_key_id")
    private String encryptionKeyId;

    /** What Google actually granted, as returned — not what was requested. A consent screen can
     *  come back with less, and that must be visible here rather than discovered on a first sync. */
    @Column(name = "granted_scopes", nullable = false)
    private String grantedScopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status = Status.CONNECTED;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /**
     * When discovery last finished checking this mailbox.
     *
     * <p>Deliberately distinct from {@link #lastSyncedAt}, which V80 reserved for actual transaction
     * sync and which stays null throughout C4: conflating "we looked" with "we imported something"
     * would make a status panel lie in exactly the period where nothing is imported yet.
     *
     * <p>It is also the window anchor — {@code GmailMessageDiscoveryService} asks Gmail for mail
     * after this, minus an overlap. Null means never checked, which is what selects the bounded
     * first-run window rather than a walk of the entire mailbox.
     */
    @Column(name = "last_discovery_at")
    private Instant lastDiscoveryAt;

    /** Consecutive discovery runs that did not complete cleanly since the last one that did. Reset
     *  to zero by {@link #recordDiscoverySuccess}; never a lifetime count. */
    @Column(name = "discovery_failure_count", nullable = false)
    private int discoveryFailureCount = 0;

    /** Earliest time this connection is due for discovery again after a failure -- the backoff
     *  {@link GmailConnectionRepository#findDueForDiscovery} excludes it until. Null means no
     *  backoff is in effect. */
    @Column(name = "discovery_retry_after")
    private Instant discoveryRetryAfter;

    /**
     * When "Sync Now" was last attempted for this connection -- set unconditionally at the start
     * of {@code GmailManualSyncService.syncNow}, whether that attempt goes on to succeed or fail.
     *
     * <p>Deliberately distinct from {@link #lastDiscoveryAt}, which only advances on a discovery
     * run that completes cleanly: a connection whose discovery keeps failing would never update
     * {@code lastDiscoveryAt} at all, which is exactly the mailbox a spam-click cooldown most needs
     * to catch. This field exists solely to answer "was a sync attempted recently", independent of
     * whether it worked.
     */
    @Column(name = "last_manual_sync_attempted_at")
    private Instant lastManualSyncAttemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Whether Google actually granted the scope this integration cannot work without.
     *
     * <p>{@link #grantedScopes} was recorded from Phase B precisely because a consent screen can
     * come back with less than was asked for — and until C2 nothing inspected it. A connection
     * missing this scope is {@code CONNECTED}, has a working refresh token, and cannot read a
     * single message.
     */
    public boolean hasGmailReadScope() {
        if (grantedScopes == null || grantedScopes.isBlank()) return false;
        for (String scope : grantedScopes.split(" ")) {
            if (GmailApiClient.GMAIL_READONLY_SCOPE.equals(scope.trim())) return true;
        }
        return false;
    }

    /** Reassembles the stored halves into the shape {@code EncryptionService.decrypt} takes.
     *  Returns null for a connection that no longer holds a credential. */
    public EncryptedValue credential() {
        if (encryptedRefreshToken == null || encryptionKeyId == null) return null;
        return new EncryptedValue(encryptionKeyId, encryptedRefreshToken);
    }

    /** Stores an already-encrypted refresh token. Takes {@link EncryptedValue} rather than two
     *  strings so a caller cannot pair a ciphertext with the wrong key id. */
    public void storeCredential(EncryptedValue value) {
        this.encryptedRefreshToken = value.ciphertext();
        this.encryptionKeyId = value.keyId();
        touch();
    }

    /**
     * Ends this connection: the credential is cleared, the row is kept.
     *
     * <p>Clearing rather than deleting is deliberate — "this user connected this mailbox on this
     * date and disconnected on that one" is exactly what an account-activity view (and a support
     * question) needs, and none of it requires keeping a live credential around to answer.
     */
    public void close(Status terminalStatus) {
        this.status = terminalStatus;
        this.encryptedRefreshToken = null;
        this.encryptionKeyId = null;
        touch();
    }

    public void markReauthRequired() {
        this.status = Status.REAUTH_REQUIRED;
        touch();
    }

    /** The cap on discovery backoff: a mailbox stuck failing is still tried at least once every
     *  six hours, never pushed out indefinitely. */
    private static final long MAX_DISCOVERY_BACKOFF_MINUTES = Duration.ofHours(6).toMinutes();

    /**
     * Records a discovery run that did not complete cleanly -- a Gmail rate limit, a transient
     * 5xx, a token-refresh hiccup. {@code discoverFor} deliberately lets that failure propagate
     * rather than catching it (the run is the unit of retry), so this is called from each caller's
     * own catch block instead.
     *
     * <p>Backs the connection off exponentially (2 minutes doubling, capped at six hours) rather
     * than disabling it: {@code findDueForDiscovery} excludes it only until
     * {@link #discoveryRetryAfter} passes, so a mailbox that keeps tripping a rate limit stops
     * re-entering the front of every tick's slice without ever losing its place in the queue for
     * good.
     */
    public void recordDiscoveryFailure(Instant now) {
        this.discoveryFailureCount++;
        long minutes = Math.min(1L << Math.min(discoveryFailureCount, 20), MAX_DISCOVERY_BACKOFF_MINUTES);
        this.discoveryRetryAfter = now.plus(Duration.ofMinutes(minutes));
        touch();
    }

    /** Records a discovery run that reached the end of its window cleanly: advances the checkpoint
     *  and clears whatever backoff a prior run's failures had built up. */
    public void recordDiscoverySuccess(Instant checkedAt) {
        this.lastDiscoveryAt = checkedAt;
        this.discoveryFailureCount = 0;
        this.discoveryRetryAfter = null;
        touch();
    }

    /** Records that "Sync Now" was attempted, regardless of outcome -- see
     *  {@link #lastManualSyncAttemptedAt}'s own doc comment for why this must not be conflated
     *  with {@link #recordDiscoverySuccess}. */
    public void recordManualSyncAttempt(Instant attemptedAt) {
        this.lastManualSyncAttemptedAt = attemptedAt;
        touch();
    }

    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getGoogleUserId() { return googleUserId; }
    public void setGoogleUserId(String googleUserId) { this.googleUserId = googleUserId; }
    public String getGoogleEmail() { return googleEmail; }
    public void setGoogleEmail(String googleEmail) { this.googleEmail = googleEmail; }
    public String getEncryptedRefreshToken() { return encryptedRefreshToken; }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public String getGrantedScopes() { return grantedScopes; }
    public void setGrantedScopes(String grantedScopes) { this.grantedScopes = grantedScopes; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; touch(); }
    public Instant getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public Instant getLastDiscoveryAt() { return lastDiscoveryAt; }
    public void setLastDiscoveryAt(Instant lastDiscoveryAt) { this.lastDiscoveryAt = lastDiscoveryAt; touch(); }
    public int getDiscoveryFailureCount() { return discoveryFailureCount; }
    public Instant getDiscoveryRetryAfter() { return discoveryRetryAfter; }
    public Instant getLastManualSyncAttemptedAt() { return lastManualSyncAttemptedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
