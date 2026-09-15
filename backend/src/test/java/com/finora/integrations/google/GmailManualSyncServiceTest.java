package com.finora.integrations.google;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Phase C5.4, D-15 — "Sync Now". */
class GmailManualSyncServiceTest {

    private static final long COOLDOWN_MS = 60_000L;

    private GmailConnectionService connectionService;
    private GmailConnectionRepository connections;
    private GmailMessageDiscoveryService discovery;
    private GmailReceiptExtractionService extraction;
    private EntitlementService entitlementService;
    private GmailManualSyncService manualSync;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionService = mock(GmailConnectionService.class);
        connections = mock(GmailConnectionRepository.class);
        discovery = mock(GmailMessageDiscoveryService.class);
        extraction = mock(GmailReceiptExtractionService.class);
        // Entitled by default -- every existing test here is about sync mechanics (cooldown,
        // error mapping), not billing. The denial test below overrides this.
        entitlementService = mock(EntitlementService.class);
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.GMAIL_SYNC)).thenReturn(true);

        // Same pattern GmailMessageDiscoveryServiceTest uses: runs the callback synchronously
        // against a mock TransactionStatus, so recordManualSyncAttempt's write actually happens.
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        manualSync = new GmailManualSyncService(connectionService, connections, discovery, extraction,
                entitlementService, transactionTemplate, COOLDOWN_MS, 500, 50);
    }

    @Test
    @DisplayName("a caller not entitled to GMAIL_SYNC is refused before the connection is even looked up")
    void notEntitledThrows403() {
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.GMAIL_SYNC)).thenReturn(false);

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(connectionService, connections, discovery, extraction);
    }

    @Test
    @DisplayName("no live connection is a 404, not a silent no-op")
    void noConnectionThrows404() {
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(connections, discovery, extraction);
    }

    @Test
    @DisplayName("a mailbox never synced before (lastManualSyncAttemptedAt null) is not rate-limited")
    void neverSyncedIsNotRateLimited() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));

        manualSync.syncNow(userId);

        verify(discovery).discoverFor(connection, 500);
        verify(extraction).extractFor(connection, 50);
    }

    @Test
    @DisplayName("syncing again inside the cooldown window is rejected, not silently rerun")
    void withinCooldownThrows429() {
        GmailConnection connection = connection(Instant.now().minusMillis(COOLDOWN_MS / 2));
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(discovery, extraction);
    }

    @Test
    @DisplayName("a mailbox last synced before the cooldown window elapsed syncs again")
    void outsideCooldownSyncsAgain() {
        GmailConnection connection = connection(Instant.now().minusMillis(COOLDOWN_MS * 2));
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));

        manualSync.syncNow(userId);

        verify(discovery).discoverFor(connection, 500);
        verify(extraction).extractFor(connection, 50);
    }

    /**
     * The bug this whole cooldown rework exists to fix. The old cooldown read
     * {@code lastDiscoveryAt}, which only advances when discovery completes cleanly -- a mailbox
     * whose discovery keeps failing (a rate limit, an unverified app's throttled quota) would
     * never trip it at all, so the button could be pressed every second with zero cooldown.
     * {@code discovery} is fully mocked here and never touches {@code lastDiscoveryAt}, which
     * stays null across both calls -- exactly the stuck-connection shape being tested.
     */
    @Test
    @DisplayName("a mailbox whose discovery never succeeds is still rate-limited on repeated manual syncs")
    void aConnectionWhoseDiscoveryNeverSucceedsIsStillRateLimited() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new RuntimeException("Gmail rate limit reached")).when(discovery).discoverFor(any(), anyInt());

        manualSync.syncNow(userId);
        assertThat(connection.getLastDiscoveryAt()).isNull();

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    /** The attempt is recorded before discovery/extraction run at all, and regardless of what they
     *  go on to do -- a failed attempt must count toward the cooldown exactly as much as a
     *  successful one, or the connection most likely to be spammed would be the one cooldown
     *  never catches. */
    @Test
    @DisplayName("the sync attempt is recorded even when both discovery and extraction fail")
    void theAttemptIsRecordedEvenWhenTheWholeSyncFails() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new RuntimeException("timeout")).when(discovery).discoverFor(any(), anyInt());
        doThrow(new RuntimeException("timeout")).when(extraction).extractFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId)).isInstanceOf(ApiException.class);

        assertThat(connection.getLastManualSyncAttemptedAt()).isNotNull();
    }

    @Test
    @DisplayName("a dead grant surfaces as a clear reconnect-needed error, not a generic failure")
    void reauthRequiredMapsToConflict() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new GmailReauthRequiredException("dead grant")).when(discovery).discoverFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("a missing scope surfaces as a clear reconnect-needed error too")
    void scopeNotGrantedMapsToConflict() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new GmailScopeNotGrantedException("missing scope")).when(discovery).discoverFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** Discovery and extraction are attempted independently now: a transient discovery failure
     *  alone is not surfaced as an error, since extraction may still drain an existing backlog.
     *  Only when BOTH legs fail is there genuinely nothing to show the user for this sync. */
    @Test
    @DisplayName("a transient failure surfaces as a retryable error only when extraction also fails")
    void transientFailureMapsToBadGatewayOnlyWhenExtractionAlsoFails() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new RuntimeException("timeout")).when(discovery).discoverFor(any(), anyInt());
        doThrow(new RuntimeException("token fetch failed")).when(extraction).extractFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    /** The fix this test exists for: a rate-limited or timed-out discovery pass must not stop
     *  extraction from draining an existing {@code DETECTED_NOT_STAGED} backlog, and the caller
     *  sees a normal (non-throwing) sync when extraction succeeds even though discovery did not. */
    @Test
    @DisplayName("a transient discovery failure does not stop extraction from still running")
    void transientDiscoveryFailureDoesNotStopExtraction() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new RuntimeException("timeout")).when(discovery).discoverFor(any(), anyInt());

        manualSync.syncNow(userId);

        verify(extraction).extractFor(connection, 50);
    }

    /** A "Sync Now" failure counts toward the same backoff {@code GmailDiscoveryWorker}'s failures
     *  do -- a user hammering the button during a Gmail outage must not reset it. Recorded
     *  regardless of whether extraction goes on to succeed. */
    @Test
    @DisplayName("a transient failure also records a discovery backoff on the connection")
    void transientFailureRecordsDiscoveryBackoff() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new RuntimeException("timeout")).when(discovery).discoverFor(any(), anyInt());

        manualSync.syncNow(userId);

        verify(discovery).recordDiscoveryFailure(connection);
    }

    /** A dead grant already removes the connection from the due query by changing its status (see
     *  {@code GmailAccessTokenService}), so recording a discovery failure on top would be redundant
     *  bookkeeping on a row nothing will read again until the user reconnects. */
    @Test
    @DisplayName("a dead grant does not record a discovery backoff")
    void reauthRequiredDoesNotRecordDiscoveryBackoff() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new GmailReauthRequiredException("dead grant")).when(discovery).discoverFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId)).isInstanceOf(ApiException.class);

        verify(discovery, never()).recordDiscoveryFailure(any());
    }

    /**
     * Unlike a dead grant, a missing scope does NOT change the connection's status, so without this
     * it would recur on every scheduled tick forever. It also matters because {@code
     * GmailApiClient} currently classifies every Gmail 403 this way -- including the rate-limit 403
     * Gmail answers a spent quota with -- so this is, today, also where a rate-limited connection's
     * failure needs to be recorded. See {@code GmailDiscoveryWorker}'s own catch for the same
     * reasoning.
     */
    @Test
    @DisplayName("a missing scope still records a discovery backoff")
    void scopeNotGrantedRecordsDiscoveryBackoff() {
        GmailConnection connection = connection(null);
        when(connectionService.findLiveConnection(userId)).thenReturn(Optional.of(connection));
        doThrow(new GmailScopeNotGrantedException("missing scope")).when(discovery).discoverFor(any(), anyInt());

        assertThatThrownBy(() -> manualSync.syncNow(userId)).isInstanceOf(ApiException.class);

        verify(discovery).recordDiscoveryFailure(connection);
    }

    /** {@code lastManualSyncAttemptedAt} rather than {@code lastDiscoveryAt} -- the field the
     *  cooldown actually reads now. Also wires {@code connections.findById} to return this same
     *  instance, since {@code recordManualSyncAttempt} re-reads before writing (same discipline
     *  {@code GmailMessageDiscoveryService.markDiscovered} uses) -- without this every test would
     *  fail the moment syncNow tries to persist the attempt. */
    private GmailConnection connection(Instant lastManualSyncAttemptedAt) {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setStatus(GmailConnection.Status.CONNECTED);
        connection.recordManualSyncAttempt(lastManualSyncAttemptedAt);
        when(connections.findById(any())).thenReturn(Optional.of(connection));
        return connection;
    }
}
