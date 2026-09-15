package com.finora.integrations.google;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.service.EntitlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * "Sync Now" — C5.4. The one-user, one-request equivalent of {@link GmailDiscoveryWorker}'s tick:
 * same two calls, each attempted independently the same way, run synchronously for the caller's own
 * connection instead of a scheduled slice of everyone's. Deliberately a separate class rather than
 * a new method on {@code GmailDiscoveryWorker} — that class's own doc comment scopes it to
 * "when discovery runs and for whom" on a schedule; this is a different trigger (a user action) and
 * a different quantity (one connection, blocking), not a variant of the same concern.
 *
 * <h2>Why its own cooldown, not the worker's {@code minimum-interval-ms}</h2>
 *
 * The worker's interval (default 1 hour) governs background cost across every mailbox. A user who
 * just pressed "Sync Now" is not asking to wait an hour before pressing it again — they are asking
 * "check right now", and a much shorter cooldown is enough to stop double-click/refresh-spam from
 * turning one click into a burst of Gmail API calls for the same mailbox.
 *
 * <p>Keyed on {@link GmailConnection#getLastManualSyncAttemptedAt()}, not
 * {@link GmailConnection#getLastDiscoveryAt()} — the latter only advances when discovery completes
 * cleanly, so a mailbox whose discovery keeps failing would never trip this cooldown at all under
 * the old key, which is backwards: that is exactly the mailbox most likely to be pressed repeatedly
 * and least able to afford it. See {@code lastManualSyncAttemptedAt}'s own doc comment.
 */
@Service
public class GmailManualSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailManualSyncService.class);

    private final GmailConnectionService connectionService;
    private final GmailConnectionRepository connections;
    private final GmailMessageDiscoveryService discovery;
    private final GmailReceiptExtractionService extraction;
    private final EntitlementService entitlementService;
    private final TransactionTemplate transactionTemplate;
    private final Duration cooldown;
    private final int messagesPerConnection;
    private final int extractionMessagesPerConnection;

    public GmailManualSyncService(
            GmailConnectionService connectionService,
            GmailConnectionRepository connections,
            GmailMessageDiscoveryService discovery,
            GmailReceiptExtractionService extraction,
            EntitlementService entitlementService,
            TransactionTemplate transactionTemplate,
            @Value("${app.integrations.google.discovery.manual-sync-cooldown-ms:60000}") long cooldownMs,
            @Value("${app.integrations.google.discovery.messages-per-connection:500}") int messagesPerConnection,
            @Value("${app.integrations.google.discovery.extraction-messages-per-connection:50}") int extractionMessagesPerConnection) {
        this.connectionService = connectionService;
        this.connections = connections;
        this.discovery = discovery;
        this.extraction = extraction;
        this.entitlementService = entitlementService;
        this.transactionTemplate = transactionTemplate;
        this.cooldown = Duration.ofMillis(cooldownMs);
        this.messagesPerConnection = messagesPerConnection;
        this.extractionMessagesPerConnection = extractionMessagesPerConnection;
    }

    /**
     * Runs discovery then extraction for this user's live connection, right now.
     *
     * <p>Entitlement is checked before the connection lookup, deliberately: a Free/Plus user who
     * downgraded after connecting (or reaches this endpoint directly) should be told to upgrade,
     * not "no connected account" -- the account may well still exist, just no longer usable here.
     *
     * @throws ApiException 403 if the caller isn't entitled to GMAIL_SYNC, 404 if there is no live
     *         connection, 429 if the cooldown hasn't elapsed
     */
    public void syncNow(UUID userId) {
        if (!entitlementService.hasEntitlement(userId, FeatureEntitlement.GMAIL_SYNC)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }

        GmailConnection connection = connectionService.findLiveConnection(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No connected Gmail account."));

        // Keyed on when a sync was last ATTEMPTED, not last_discovery_at (when one last completed
        // cleanly). A connection whose discovery keeps failing never advances last_discovery_at at
        // all -- see GmailConnection.lastManualSyncAttemptedAt's own doc comment -- which used to
        // mean the one mailbox most likely to need this cooldown had none.
        Instant lastAttempt = connection.getLastManualSyncAttemptedAt();
        if (lastAttempt != null && lastAttempt.isAfter(Instant.now().minus(cooldown))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Gmail was synced recently -- try again in a moment.");
        }
        // Recorded before discovery/extraction run, and regardless of what they go on to do -- the
        // cooldown exists to bound how often THIS mailbox gets hit with Gmail API calls, which is
        // exactly as true of a call that is about to fail as one that succeeds.
        recordManualSyncAttempt(connection, Instant.now());

        // Discovery and extraction are attempted independently, not inside one try/catch -- same
        // reasoning as GmailDiscoveryWorker.runOnce, one level down to a single connection. A user
        // tapping "Sync Now" while their own discovery pass is transiently failing (a rate limit, a
        // timeout) should still get whatever extraction can drain from an existing
        // DETECTED_NOT_STAGED backlog, rather than an error that skips extraction entirely. Only a
        // dead grant or a missing scope skips extraction too, since both mean the same token fetch
        // extraction would make fails identically.
        boolean discoveryFailedTransiently = false;
        try {
            discovery.discoverFor(connection, messagesPerConnection);
        } catch (GmailReauthRequiredException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Gmail connection needs to be reconnected before syncing.");
        } catch (GmailScopeNotGrantedException e) {
            // Also counts toward the connection's discovery backoff -- see GmailDiscoveryWorker's
            // own catch for why this exception isn't necessarily a genuine missing-scope refusal.
            discovery.recordDiscoveryFailure(connection);
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Gmail connection is missing the permission needed to read mail -- reconnect to grant it.");
        } catch (RuntimeException e) {
            // Transient by elimination, same reasoning as GmailDiscoveryWorker's own catch. Also
            // counts toward the same backoff GmailDiscoveryWorker's failures do, so a user hammering
            // "Sync Now" during a Gmail outage still pushes their connection into the same
            // deprioritization rather than resetting it. Not thrown yet -- extraction gets its own
            // chance below before this becomes a user-facing failure.
            discovery.recordDiscoveryFailure(connection);
            log.warn("Manual Gmail discovery failed for connection {}: {}",
                    connection.getId(), e.getClass().getSimpleName());
            discoveryFailedTransiently = true;
        }

        try {
            extraction.extractFor(connection, extractionMessagesPerConnection);
        } catch (RuntimeException e) {
            // Extraction only throws here for a whole-batch failure (its own access-token fetch) --
            // per-message parser/body failures are already caught and logged inside
            // GmailReceiptExtractionService itself and never reach this catch.
            log.warn("Manual Gmail extraction failed for connection {}: {}",
                    connection.getId(), e.getClass().getSimpleName());
            if (discoveryFailedTransiently) {
                // Both legs failed: genuinely nothing happened this sync, which is what the old
                // combined catch surfaced as a 502. A discovery-only failure with extraction working
                // (or vice versa) is not surfaced as an error -- the user still gets whatever the
                // half that worked found.
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Gmail sync didn't complete -- try again in a moment.");
            }
        }
    }

    /** Persists the attempt timestamp in its own short transaction, re-reading first -- same
     *  discipline {@code GmailMessageDiscoveryService.markDiscovered} uses, and for the same
     *  reason: {@code syncNow} itself deliberately holds no transaction across the Gmail calls
     *  above and below this, so a pooled connection must not be held here either. Re-reads rather
     *  than saving the caller's own (possibly stale) copy so a connection disconnected in the
     *  moment between the lookup above and this write is not resurrected by it. */
    private void recordManualSyncAttempt(GmailConnection connection, Instant now) {
        UUID connectionId = connection.getId();
        transactionTemplate.executeWithoutResult(tx ->
                connections.findById(connectionId).ifPresent(fresh -> {
                    if (fresh.getStatus() != GmailConnection.Status.CONNECTED) return;
                    fresh.recordManualSyncAttempt(now);
                    connections.save(fresh);
                }));
        connection.recordManualSyncAttempt(now);
    }
}
