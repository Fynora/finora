package com.finora.integrations.google;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.integrations.google.merchant.GmailReceiptExtractionService;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase C4 scheduling.
 *
 * <p>One property carries most of the weight here: <b>a broken mailbox must not be able to stop
 * every other mailbox from syncing.</b> Per-connection failures are the normal case for this worker
 * — expired grants, rate limits, transient 5xx — and a loop that let the first one escape would mean
 * the single most likely failure silently starves everyone behind it in the slice.
 */
class GmailDiscoveryWorkerTest {

    private GmailMessageDiscoveryService discovery;
    private GmailReceiptExtractionService extraction;
    private GmailConnectionRepository connections;
    private EntitlementService entitlementService;
    private GmailDiscoveryWorker worker;

    @BeforeEach
    void setUp() {
        discovery = mock(GmailMessageDiscoveryService.class);
        extraction = mock(GmailReceiptExtractionService.class);
        connections = mock(GmailConnectionRepository.class);

        WorkerObservability observability = mock(WorkerObservability.class);
        WorkerExecution execution = mock(WorkerExecution.class);
        when(observability.beginScheduled(anyString(), anyString())).thenReturn(execution);

        // Entitled by default -- every existing test here is about scheduling/failure-isolation
        // mechanics, not billing. The denial test below overrides this per-connection.
        entitlementService = mock(EntitlementService.class);
        when(entitlementService.hasEntitlement(any(), eq(FeatureEntitlement.GMAIL_SYNC))).thenReturn(true);

        worker = new GmailDiscoveryWorker(discovery, extraction, connections, observability, entitlementService,
                true, 25, 500, 50, Duration.ofHours(1).toMillis());
    }

    /** A connection stays live across a plan downgrade -- nothing tears it down. Without this
     *  check, a downgraded user would keep getting free background sync forever. */
    @Test
    @DisplayName("a connection whose owner is no longer entitled to GMAIL_SYNC is skipped, not synced")
    void aNoLongerEntitledConnectionIsSkipped() {
        GmailConnection downgraded = connection();
        GmailConnection healthy = connection();
        when(connections.findDueForDiscovery(any(), any(), any())).thenReturn(List.of(downgraded, healthy));
        when(entitlementService.hasEntitlement(downgraded.getUserId(), FeatureEntitlement.GMAIL_SYNC)).thenReturn(false);

        int attempted = worker.runOnce();

        assertThat(attempted).isEqualTo(2);
        verify(discovery, never()).discoverFor(eq(downgraded), anyInt());
        verify(extraction, never()).extractFor(eq(downgraded), anyInt());
        verify(discovery).discoverFor(healthy, 500);
        verify(extraction).extractFor(healthy, 50);
    }

    /**
     * The test this class exists for. Three connections, the first two broken in the two ways that
     * happen most often; the third must still be attempted.
     */
    @Test
    @DisplayName("one broken mailbox does not starve the rest of the slice")
    void aFailingConnectionDoesNotAbortTheTick() {
        GmailConnection deadGrant = connection();
        GmailConnection rateLimited = connection();
        GmailConnection healthy = connection();
        when(connections.findDueForDiscovery(any(), any(), any()))
                .thenReturn(List.of(deadGrant, rateLimited, healthy));

        doThrow(new GmailReauthRequiredException("grant is gone"))
                .when(discovery).discoverFor(eq(deadGrant), anyInt());
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Gmail is unavailable."))
                .when(discovery).discoverFor(eq(rateLimited), anyInt());

        int attempted = worker.runOnce();

        assertThat(attempted).isEqualTo(3);
        verify(discovery).discoverFor(healthy, 500);
        verify(extraction).extractFor(healthy, 50);
    }

    /**
     * The mechanism the backoff depends on: a generic (transient-by-elimination) failure must tell
     * {@link GmailMessageDiscoveryService} about it, or a rate-limited mailbox keeps sorting at the
     * front of {@code findDueForDiscovery} forever -- see that repository method's own doc comment.
     * A dead grant is deliberately excluded: {@code GmailAccessTokenService} already flips its
     * status to {@code REAUTH_REQUIRED}, which is what removes it from the due query, so recording
     * a discovery failure on top would be redundant bookkeeping on a row nothing will read again
     * until the user reconnects.
     *
     * <p>A missing scope IS included, unlike a dead grant: {@code GmailApiClient} currently
     * classifies every Gmail 403 as {@link GmailScopeNotGrantedException}, including the
     * rate-limit 403 Gmail answers a spent quota with -- so today, this is also where a
     * rate-limited connection's failure actually needs to be recorded (PR #1563 narrows the
     * classification; this backoff must not depend on that landing first).
     */
    @Test
    @DisplayName("a transient failure and a missing-scope failure both record backoff; a dead grant does not")
    void aTransientFailureRecordsBackoffButAReauthFailureDoesNot() {
        GmailConnection deadGrant = connection();
        GmailConnection rateLimited = connection();
        GmailConnection noScope = connection();
        when(connections.findDueForDiscovery(any(), any(), any()))
                .thenReturn(List.of(deadGrant, rateLimited, noScope));
        doThrow(new GmailReauthRequiredException("grant is gone"))
                .when(discovery).discoverFor(eq(deadGrant), anyInt());
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Gmail is unavailable."))
                .when(discovery).discoverFor(eq(rateLimited), anyInt());
        doThrow(new GmailScopeNotGrantedException("actually a rate limit, misclassified"))
                .when(discovery).discoverFor(eq(noScope), anyInt());

        worker.runOnce();

        verify(discovery).recordDiscoveryFailure(rateLimited);
        verify(discovery).recordDiscoveryFailure(noScope);
        verify(discovery, never()).recordDiscoveryFailure(deadGrant);
    }

    /**
     * The reason discovery and extraction are one loop iteration, not two scheduled passes:
     * mail discovery just found should not wait for a later tick to be extracted.
     */
    @Test
    @DisplayName("extraction runs for a connection right after its own discovery pass, same tick")
    void extractionRunsImmediatelyAfterDiscoveryForEachConnection() {
        GmailConnection connection = connection();
        when(connections.findDueForDiscovery(any(), any(), any())).thenReturn(List.of(connection));

        worker.runOnce();

        InOrder inOrder = inOrder(discovery, extraction);
        inOrder.verify(discovery).discoverFor(connection, 500);
        inOrder.verify(extraction).extractFor(connection, 50);
    }

    /** A transient discovery failure (a rate limit, a timeout) says nothing about whether an
     *  existing {@code DETECTED_NOT_STAGED} backlog can still be drained -- extraction hits a
     *  different Gmail endpoint with a different cost profile, so it still gets its own attempt. */
    @Test
    @DisplayName("a connection whose discovery fails transiently still attempts extraction")
    void extractionStillRunsWhenDiscoveryFailsTransiently() {
        GmailConnection rateLimited = connection();
        when(connections.findDueForDiscovery(any(), any(), any())).thenReturn(List.of(rateLimited));
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Gmail is unavailable."))
                .when(discovery).discoverFor(eq(rateLimited), anyInt());

        worker.runOnce();

        verify(extraction).extractFor(rateLimited, 50);
    }

    /** Unlike a transient failure, a dead grant or a missing scope means extraction's own
     *  access-token fetch would fail identically -- attempting it anyway would just spend a
     *  second doomed request, which is the reasoning the transient case above no longer shares. */
    @Test
    @DisplayName("extraction is skipped when the grant is dead or the scope is missing")
    void extractionIsSkippedForAReauthOrScopeFailure() {
        GmailConnection deadGrant = connection();
        GmailConnection noScope = connection();
        when(connections.findDueForDiscovery(any(), any(), any()))
                .thenReturn(List.of(deadGrant, noScope));
        doThrow(new GmailReauthRequiredException("grant is gone"))
                .when(discovery).discoverFor(eq(deadGrant), anyInt());
        doThrow(new GmailScopeNotGrantedException("no gmail.readonly"))
                .when(discovery).discoverFor(eq(noScope), anyInt());

        worker.runOnce();

        verify(extraction, never()).extractFor(eq(deadGrant), anyInt());
        verify(extraction, never()).extractFor(eq(noScope), anyInt());
    }

    /**
     * A connection that consented without {@code gmail.readonly} answers 403 to everything and,
     * unlike a dead grant, keeps its {@code CONNECTED} status — so it stays in the due query and
     * recurs every tick. It must not escape the loop either.
     */
    @Test
    void aConnectionMissingTheScopeIsHandledLikeAnyOtherPerConnectionFailure() {
        GmailConnection noScope = connection();
        GmailConnection healthy = connection();
        when(connections.findDueForDiscovery(any(), any(), any())).thenReturn(List.of(noScope, healthy));
        doThrow(new GmailScopeNotGrantedException("no gmail.readonly"))
                .when(discovery).discoverFor(eq(noScope), anyInt());

        worker.runOnce();

        verify(discovery).discoverFor(healthy, 500);
    }

    /**
     * The due query's arguments are the whole rate policy — a mailbox rests for the minimum interval,
     * and a tick takes a bounded slice. Passing the wrong instant here would re-check the same
     * mailboxes every tick, which no assertion on outcomes would notice.
     */
    @Test
    void onlyConnectionsPastTheMinimumIntervalAreDue() {
        when(connections.findDueForDiscovery(any(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        worker.runOnce();

        ArgumentCaptor<Instant> checkedBefore = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(connections).findDueForDiscovery(checkedBefore.capture(), now.capture(), page.capture());

        assertThat(checkedBefore.getValue())
                .isBetween(before.minus(Duration.ofHours(1)).minusSeconds(5),
                           Instant.now().minus(Duration.ofHours(1)));
        assertThat(now.getValue()).isBetween(before.minusSeconds(5), Instant.now());
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
    }

    /**
     * The flag has to gate the SCHEDULED entry point specifically. Gating {@code runOnce} instead
     * would leave tests unable to drive the worker at all, which is why
     * {@code application-test.yml} can turn it off without disabling the tests.
     */
    @Test
    void theScheduledTriggerDoesNothingWhenDisabled() {
        GmailDiscoveryWorker disabled = new GmailDiscoveryWorker(discovery, extraction, connections,
                observabilityStub(), entitlementService, false, 25, 500, 50, 3_600_000L);

        disabled.scheduledDiscovery();

        verifyNoInteractions(connections);
        verifyNoInteractions(discovery);
        verifyNoInteractions(extraction);
    }

    private WorkerObservability observabilityStub() {
        WorkerObservability observability = mock(WorkerObservability.class);
        when(observability.beginScheduled(anyString(), anyString()))
                .thenReturn(mock(WorkerExecution.class));
        return observability;
    }

    private static GmailConnection connection() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        try {
            var field = GmailConnection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(connection, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return connection;
    }
}
