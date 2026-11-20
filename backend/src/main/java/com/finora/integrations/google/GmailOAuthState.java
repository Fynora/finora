package com.finora.integrations.google;

import com.finora.security.crypto.EncryptedValue;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One pending "user pressed Connect Gmail". Google's redirect redeems the state and parks its
 * answer here; the signed-in app then confirms with a one-time ticket, and only that connects
 * anything (V331). The state proves who started the flow, never who is finishing it.
 *
 * <h2>Why this exists at all</h2>
 *
 * Google returns by redirecting the user's BROWSER to the callback URL. That request carries no
 * Authorization header and no session — this API is stateless — so nothing on it says which Finora
 * user it belongs to. The only channel that survives the round trip is the {@code state} parameter
 * Google echoes back.
 *
 * <p>That makes {@code state} load-bearing for identity, not merely CSRF defence, and raises the
 * bar it has to clear: unguessable, bound to the user who began the flow, short-lived, and
 * <b>single use</b>. The last of those is why this is a table. A signed stateless token can carry a
 * user id and an expiry; nothing stateless can enforce "redeemable exactly once", and without that
 * a callback URL replayed out of browser history, a referrer header, or a shared screenshot links
 * the account again.
 *
 * <h2>The value is hashed, not stored</h2>
 *
 * {@link #stateHash} is SHA-256 of the state. The raw value passes through the user's browser and
 * Google's servers, so anyone holding it can complete a link for the bound user — it is a bearer
 * credential. Finora only ever needs to COMPARE a presented state against a stored one, never to
 * reproduce it, which is precisely the case hashing is for (contrast the refresh token, which must
 * be replayed to Google and is therefore encrypted instead — ADR-009). A leaked database yields no
 * usable states.
 */
@Entity
@Table(name = "gmail_oauth_states")
public class GmailOAuthState {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Which platform to send the browser back to once the callback completes -- a
     * {@link ReturnPlatform} name ({@code "WEB"} or {@code "MOBILE"}), not a URL or path despite
     * the column name. Set from a closed enum at {@code beginConnect} time (never from anything
     * request-supplied at the callback itself), so a crafted state cannot turn the callback into an
     * open redirect -- see {@link ReturnPlatform}'s own doc comment. Null on any row from before
     * this was wired up; resolved to {@link ReturnPlatform#WEB} in that case.
     */
    @Column(name = "return_path", length = 512)
    private String returnPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set on redemption. Non-null means this state has already been used, and a second callback
     *  presenting it is a replay. Kept rather than deleted so a replay stays distinguishable from
     *  an entirely unknown state. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /**
     * SHA-256 of the one-time ticket the callback hands the browser (V331). The connection is made
     * only when the signed-in app posts that ticket back, as the user this row belongs to. Hashed
     * for the same reason {@link #stateHash} is. Null until Google has redirected back.
     */
    @Column(name = "ticket_hash", length = 64)
    private String ticketHash;

    @Column(name = "ticket_expires_at")
    private Instant ticketExpiresAt;

    /** Google's authorization code, encrypted, held only between the callback and the app's
     *  confirmation. Cleared when the ticket is redeemed, whatever the outcome. */
    @Column(name = "encrypted_code")
    private String encryptedCode;

    @Column(name = "code_encryption_key_id", length = 64)
    private String codeEncryptionKeyId;

    /** Set when the ticket is redeemed. Non-null means a second post of the same ticket is a
     *  replay. */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /**
     * Holds what Google returned until the signed-in app confirms. Pushes {@link #expiresAt} out to
     * the ticket's own expiry when that is later, so the expiry sweep cannot remove a row whose
     * ticket is still good.
     */
    public void park(String ticketHash, EncryptedValue code, Instant ticketExpiresAt) {
        this.ticketHash = ticketHash;
        this.encryptedCode = code.ciphertext();
        this.codeEncryptionKeyId = code.keyId();
        this.ticketExpiresAt = ticketExpiresAt;
        if (expiresAt == null || expiresAt.isBefore(ticketExpiresAt)) {
            this.expiresAt = ticketExpiresAt;
        }
    }

    /** The parked authorization code, or null once it has been taken. */
    public EncryptedValue parkedCode() {
        if (encryptedCode == null || codeEncryptionKeyId == null) return null;
        return new EncryptedValue(codeEncryptionKeyId, encryptedCode);
    }

    public void clearParkedCode() {
        this.encryptedCode = null;
        this.codeEncryptionKeyId = null;
    }

    public String getTicketHash() { return ticketHash; }
    public Instant getTicketExpiresAt() { return ticketExpiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }

    public boolean isConsumed() { return consumedAt != null; }

    public boolean isExpired(Instant now) { return expiresAt.isBefore(now); }

    /** Redeemable exactly once, and only before it expires. */
    public boolean isRedeemable(Instant now) { return !isConsumed() && !isExpired(now); }

    public void consume(Instant now) { this.consumedAt = now; }

    public UUID getId() { return id; }
    public String getStateHash() { return stateHash; }
    public void setStateHash(String stateHash) { this.stateHash = stateHash; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getReturnPath() { return returnPath; }
    public void setReturnPath(String returnPath) { this.returnPath = returnPath; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
}
