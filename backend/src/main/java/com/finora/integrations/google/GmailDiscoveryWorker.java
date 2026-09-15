package com.finora.integrations.google;

import com.finora.entity.FeatureEntitlement;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import com.finora.service.EntitlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decides <b>when</b> discovery runs and <b>for whom</b> — Phase C4.
 *
 * <p>Split from {@link GmailMessageDiscoveryService}, which decides what a single mailbox contains.
 * The seam is deliberate: everything Gmail-shaped lives on the far side of it, so this class knows
 * about scheduling, batching and limits and nothing about the Gmail API. That is also what makes the
 * discovery logic testable without HTTP.
 *
 * <h2>Discovery, then extraction, per connection — not two separate ticks</h2>
 *
 * C5-B added {@link GmailReceiptExtractionService} immediately after {@code discoverFor} in the same
 * loop iteration, not as a second scheduled pass over the whole connection list. A connection whose
 * discovery just found new trusted mail gets that mail extracted in the SAME tick, rather than
 * waiting for a later pass to notice the {@code DETECTED_NOT_STAGED} backlog discovery just created.
 * The two remain separate classes (this file only orchestrates; neither knows about the other's
 * internals) — see each class's own doc comment for why they are split at all.
 *
 * <h2>One failed connection does not fail the tick</h2>
 *
 * A run's failures are per-connection: an expired grant, a mailbox over quota, a transient 5xx. Each
 * is caught here so the remaining connections in the slice still get their pass. A worker that
 * aborted the tick on the first bad mailbox would let one broken connection starve every other user
 * — and the broken one is precisely the one most likely to fail again next tick.
 *
 * <p>Discovery and extraction are each wrapped in their own try/catch, deliberately not one shared
 * block: a transient discovery failure on this tick does not mean extraction has nothing to do.
 * Discovery finds new mail; extraction drains whatever {@code DETECTED_NOT_STAGED} backlog already
 * exists, which can be nonempty even when today's discovery pass fails outright, and costs a
 * different Gmail request pattern (a body fetch per already-known message, not a header fetch per
 * newly-listed one). Only a dead grant or a missing scope skips extraction too — both mean the same
 * token fetch extraction would make fails identically.
 *
 * <h2>A second, independent slice for backed-off connections</h2>
 *
 * {@code findDueForDiscovery} excludes a connection entirely once its discovery backoff is active
 * ({@code discoveryRetryAfter} in the future), which can be hours after repeated failures. That
 * exclusion is right for discovery, but it also means the try/catch decoupling above only helps on
 * the tick a connection's discovery failure first happens — every tick after that, the connection
 * never reaches the loop at all, extraction included, even though its own request pattern may still
 * succeed. {@code findWithPendingExtraction} is a second query, run every tick regardless of any
 * connection's backoff state, for connections carrying an unprocessed backlog; {@link #runOnce()}
 * attempts extraction for whatever it returns that the first slice did not already handle this tick.
 *
 * <h2>No retry loop</h2>
 *
 * Nothing here retries. The run IS the unit of retry: {@code gmail_processed_messages} records what
 * was already decided, so the next tick resumes rather than restarts. Retrying inside the loop would
 * spend requests on a mailbox that is rate-limited or on a grant that is dead, which are the two
 * most common failures.
 */
@Component
public class GmailDiscoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(GmailDiscoveryWorker.class);

    private static final String WORKER = "gmail-discovery";
    private static final String JOB_KIND = "mailbox-scan";

    private final GmailMessageDiscoveryService discovery;
    private final GmailReceiptExtractionService extraction;
    private final GmailConnectionRepository connections;
    private final WorkerObservability observability;
    private final EntitlementService entitlementService;

    private final boolean enabled;
    private final int connectionsPerTick;
    private final int messagesPerConnection;
    private final int extractionMessagesPerConnection;
    private final Duration minimumInterval;

    public GmailDiscoveryWorker(
            GmailMessageDiscoveryService discovery,
            GmailReceiptExtractionService extraction,
            GmailConnectionRepository connections,
            WorkerObservability observability,
            EntitlementService entitlementService,
            @Value("${app.integrations.google.discovery.enabled:true}") boolean enabled,
            @Value("${app.integrations.google.discovery.connections-per-tick:25}") int connectionsPerTick,
            @Value("${app.integrations.google.discovery.messages-per-connection:500}") int messagesPerConnection,
            // Smaller than discovery's own cap on purpose: a discovery message costs one header
            // fetch, an extraction message costs a body fetch plus parsing plus a staging write --
            // meaningfully more expensive per message, so its own ceiling is lower.
            @Value("${app.integrations.google.discovery.extraction-messages-per-connection:50}") int extractionMessagesPerConnection,
            @Value("${app.integrations.google.discovery.minimum-interval-ms:3600000}") long minimumIntervalMs) {
        this.discovery = discovery;
        this.extraction = extraction;
        this.connections = connections;
        this.observability = observability;
        this.entitlementService = entitlementService;
        this.enabled = enabled;
        this.connectionsPerTick = connectionsPerTick;
        this.messagesPerConnection = messagesPerConnection;
        this.extractionMessagesPerConnection = extractionMessagesPerConnection;
        this.minimumInterval = Duration.ofMillis(minimumIntervalMs);
    }

    /**
     * The scheduled trigger.
     *
     * <p>{@code fixedDelay}, never {@code fixedRate}: a tick that runs long must not have the next
     * one start on top of it. Overlapping ticks would double Gmail's request rate for the same
     * mailboxes at exactly the moment the API is already slow.
     *
     * <p>Flag-gated for the reason {@code GmailConnectionService.scheduledStateSweep} gives: an
     * integration suite needs deterministic state, and a background thread making outbound HTTP
     * calls mid-test is the cross-test pollution BH-058 was about. {@code application-test.yml}
     * turns it off and tests call {@link #runOnce()} directly.
     */
    @Scheduled(fixedDelayString = "${app.integrations.google.discovery.interval-ms:900000}",
               initialDelayString = "${app.integrations.google.discovery.initial-delay-ms:180000}")
    public void scheduledDiscovery() {
        if (!enabled) return;
        runOnce();
    }

    /**
     * Runs one pass over a bounded slice of connections.
     *
     * <p>Two independent slices, not one: {@code findDueForDiscovery} (discovery, plus an
     * opportunistic extraction attempt right after) and {@code findWithPendingExtraction}
     * (extraction only, for a connection currently excluded from the first slice by discovery's own
     * backoff but still carrying an unprocessed backlog). See {@link
     * GmailConnectionRepository#findWithPendingExtraction} for why that backoff must not also gate
     * extraction. A connection already handled by the first slice this tick is never attempted again
     * by the second.
     *
     * <p>Public and synchronous so tests can drive it deterministically rather than waiting on a
     * scheduler — the same reason {@code ImportJobWorker.drainOnce} is.
     *
     * @return how many connections were attempted, across both slices
     */
    public int runOnce() {
        try (WorkerExecution execution = observability.beginScheduled(WORKER, JOB_KIND)) {
            // Connections checked within the minimum interval are not due. Without this the slice
            // would return the same first N mailboxes every tick and mailboxes beyond the slice
            // would never be reached -- the ordering makes that starvation quiet rather than
            // visible, which is worse.
            List<GmailConnection> due = connections.findDueForDiscovery(
                    Instant.now().minus(minimumInterval),
                    Instant.now(),
                    PageRequest.of(0, connectionsPerTick));
            execution.claimed(due.size());

            Set<UUID> attempted = new HashSet<>();
            for (GmailConnection connection : due) {
                attempted.add(connection.getId());
                if (!isEntitled(connection)) continue;

                // Discovery and extraction are attempted independently, not inside one try/catch:
                // discovery finding nothing new this tick (or failing outright) does not mean
                // extraction has nothing to do -- a mailbox can carry a DETECTED_NOT_STAGED backlog
                // from an earlier successful discovery run, and extraction's own Gmail cost (one body
                // fetch per already-known message) is a different request pattern than discovery's
                // (header fetches over newly-listed mail). A transient discovery failure must not
                // block a backlog that discovery itself is not needed to drain. REAUTH_REQUIRED and
                // a missing scope are the exception -- both mean extraction would fail identically
                // fetching the same dead/unscoped token, so those skip extraction too.
                boolean canExtract = true;
                try {
                    discovery.discoverFor(connection, messagesPerConnection);
                } catch (GmailReauthRequiredException e) {
                    // Expected, not exceptional. GmailAccessTokenService has already flipped the
                    // connection to REAUTH_REQUIRED, which is what removes it from the due query --
                    // so this resolves itself and needs no alert.
                    log.info("Gmail connection {} needs reconnecting; skipping discovery.",
                            connection.getId());
                    canExtract = false;
                } catch (GmailScopeNotGrantedException e) {
                    // Usually the user completed consent without gmail.readonly -- permanent until
                    // they reconnect, and unlike a dead grant it does NOT change the status, so this
                    // would recur every tick without the same backoff the generic catch below gets.
                    // PR #1563 narrowed GmailApiClient.get()'s 403 handling to only classify a true
                    // scope refusal this way; a spent per-user quota now throws ApiException instead
                    // (the generic catch below), so this branch no longer doubles as the rate-limit
                    // case it once did.
                    discovery.recordDiscoveryFailure(connection);
                    log.warn("Gmail connection {} lacks the readonly scope; discovery cannot run.",
                            connection.getId());
                    canExtract = false;
                } catch (RuntimeException e) {
                    // Transient by elimination: a timeout, a 5xx, a rate limit. The next tick
                    // resumes from what was recorded, so this is a delay rather than a loss --
                    // but a mailbox that keeps landing here needs to back off, or it re-enters the
                    // front of findDueForDiscovery's order every tick and can crowd out the rest of
                    // the slice. See GmailConnection.recordDiscoveryFailure. Extraction is still
                    // attempted below: the connection's token and scope are fine, only today's
                    // discovery pass failed, so a body-fetch backlog left by a previous clean
                    // discovery run may still succeed.
                    discovery.recordDiscoveryFailure(connection);
                    log.warn("Gmail discovery failed for connection {}: {}",
                            connection.getId(), e.getClass().getSimpleName());
                }

                if (canExtract) {
                    attemptExtraction(connection, execution);
                }
            }

            // Backed-off connections never reach the loop above at all -- findDueForDiscovery
            // excludes anything whose discoveryRetryAfter has not passed, which can be hours after
            // repeated failures. This second slice is what still lets extraction drain an existing
            // backlog on exactly the connections the first slice is (rightly, for discovery)
            // ignoring. See findWithPendingExtraction's own doc comment.
            //
            // Requests more than connectionsPerTick rows on purpose. findWithPendingExtraction
            // orders by connection id -- a stable order unrelated to findDueForDiscovery's
            // lastDiscoveryAt ordering -- so its first page can legitimately consist entirely of
            // connections already in `due` above (dedup then skips every one of them). Without the
            // headroom, that page-0 overlap would silently waste the entire tick's extraction-only
            // budget on connections already handled, leaving the actually-backed-off ones behind it
            // in id order unreached -- and since the ordering is stable, the same overlap would
            // recur every tick rather than resolving itself. due.size() is always <= connectionsPerTick
            // (it is that query's own page size), so requesting connectionsPerTick + due.size() rows
            // guarantees at least connectionsPerTick genuinely-new candidates are available even in
            // the worst case where every one of `due` appears before any of them.
            List<GmailConnection> pendingExtractionCandidates = connections.findWithPendingExtraction(
                    PageRequest.of(0, connectionsPerTick + due.size()));
            execution.claimed(pendingExtractionCandidates.size());

            int extractionOnlyAttempted = 0;
            for (GmailConnection connection : pendingExtractionCandidates) {
                if (extractionOnlyAttempted >= connectionsPerTick) break;
                // Already handled (or skipped) by the discovery-due loop above this same tick --
                // attempting extraction a second time would just spend a duplicate request.
                if (attempted.contains(connection.getId())) continue;
                if (!isEntitled(connection)) continue;

                extractionOnlyAttempted++;
                attemptExtraction(connection, execution);
            }

            return due.size() + extractionOnlyAttempted;
        }
    }

    /** A connection stays live across a plan downgrade -- GmailConnectionService only ever refuses
     *  a NEW connect, it never tears an existing one down. Without this check, a Premium user who
     *  downgrades keeps getting free background sync forever, which is exactly the ongoing cost
     *  GMAIL_SYNC exists to gate. Checked here rather than folded into either repository query, to
     *  keep entitlement lookups (EntitlementService, the billing domain) out of a plain connection
     *  repository -- and shared by both slices in {@link #runOnce()} so a downgraded user is skipped
     *  by extraction-only too, not just by discovery. */
    private boolean isEntitled(GmailConnection connection) {
        if (entitlementService.hasEntitlement(connection.getUserId(), FeatureEntitlement.GMAIL_SYNC)) {
            return true;
        }
        log.info("Gmail connection {} is no longer entitled to GMAIL_SYNC; skipping sync.",
                connection.getId());
        return false;
    }

    private void attemptExtraction(GmailConnection connection, WorkerExecution execution) {
        try {
            extraction.extractFor(connection, extractionMessagesPerConnection);
            execution.completed(connection.getId());
        } catch (RuntimeException e) {
            // Extraction only throws here for a whole-batch failure (its own access-token fetch) --
            // per-message parser/body failures are already caught and logged inside
            // GmailReceiptExtractionService itself and never reach this catch.
            log.warn("Gmail extraction failed for connection {}: {}",
                    connection.getId(), e.getClass().getSimpleName());
        }
    }
}
