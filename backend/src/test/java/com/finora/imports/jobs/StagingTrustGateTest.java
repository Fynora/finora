package com.finora.imports.jobs;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagingSessionResponse;
import com.finora.entity.HeldStatement;
import com.finora.exception.ApiException;
import com.finora.imports.ImportSessionService;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.storage.ContentAddress;
import com.finora.service.HeldStatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StagingTrustGate}'s decisions and its fail-closed paths, which a working database and
 * storage never reach in an integration test.
 */
class StagingTrustGateTest {

    private final HeldStatementService heldStatementService = mock(HeldStatementService.class);
    private final ImportJobService importJobService = mock(ImportJobService.class);
    private final ImportSessionService importSessionService = mock(ImportSessionService.class);
    private final ParserVersionProvider parserVersionProvider = mock(ParserVersionProvider.class);
    private final ImportVerificationRecorder verificationRecorder = mock(ImportVerificationRecorder.class);
    private final StagingTrustGate gate = new StagingTrustGate(heldStatementService, importJobService,
            importSessionService, parserVersionProvider, verificationRecorder);

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final byte[] CONTENT = "date,amount\n".getBytes(StandardCharsets.UTF_8);
    private static final ImportJobService.StoredUpload STORED =
            new ImportJobService.StoredUpload(new ContentAddress("hash", "objects/key"), "key-1");

    @BeforeEach
    void noReviewYet() {
        when(heldStatementService.reviewOfStagedSession(SESSION)).thenReturn(Optional.empty());
    }

    private static StagingSessionResponse staged(LocalDate periodStart, LocalDate periodEnd) {
        ImportDto.DetectedAccountInfo account = new ImportDto.DetectedAccountInfo(
                "Sample Bank", "SAVINGS", null, null, periodStart, periodEnd, null, null, null, null,
                null, null, null, null, "SAVINGS", 0.9, false, List.of(), null,
                null, null, null, null, null, null, null);
        return new StagingSessionResponse(SESSION,
                new ImportDto.StagingResponse(List.of(), 2, 0, account, List.of()));
    }

    private static StagingSessionResponse distrusted() {
        LocalDate future = LocalDate.now().plusYears(3);
        return staged(future, future.plusDays(30));
    }

    @Test
    void aTrustedStatementPassesThroughWithNothingStoredOrHeld() throws Exception {
        StagingSessionResponse response = staged(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(gate.check(USER, "statement.csv", CONTENT, response)).isSameAs(response);
        verify(importJobService, never()).storeForHold(any());
        verify(heldStatementService, never()).holdStagedUpload(any(), any(), any(), any(), any(), any(), any(),
                any(), anyBoolean());
    }

    @Test
    void aDistrustedStatementIsHeldAndTheResponseNamesTheJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(importJobService.storeForHold(CONTENT)).thenReturn(STORED);
        when(heldStatementService.holdStagedUpload(eq(USER), eq("statement.csv"), eq("CSV"), eq(STORED.address()),
                eq("key-1"), any(), any(), any(), eq(false)))
                .thenReturn(new HeldStatementService.StagedHold(jobId, true));

        StagingSessionResponse result = gate.check(USER, "statement.csv", CONTENT, distrusted());

        assertThat(result.heldForReviewJobId()).isEqualTo(jobId);
        assertThat(result.sessionId()).isEqualTo(SESSION);
        verify(importJobService, never()).discardStoredForHold(any());
        verify(importSessionService, never()).discardUnlessUnderReview(any());
    }

    @Test
    void aSessionWithAnOpenReviewFollowsItWithoutStoringAnotherCopy() throws Exception {
        UUID existingJob = UUID.randomUUID();
        when(heldStatementService.reviewOfStagedSession(SESSION)).thenReturn(Optional.of(
                new HeldStatementService.StagedSessionReview(existingJob, HeldStatement.Status.INVESTIGATING)));

        StagingSessionResponse result = gate.check(USER, "statement.csv", CONTENT, distrusted());

        assertThat(result.heldForReviewJobId()).isEqualTo(existingJob);
        verify(importJobService, never()).storeForHold(any());
    }

    @Test
    void aSessionWhoseReviewWasApprovedIsNotJudgedAgain() throws Exception {
        when(heldStatementService.reviewOfStagedSession(SESSION)).thenReturn(Optional.of(
                new HeldStatementService.StagedSessionReview(null, HeldStatement.Status.IMPORTED)));

        StagingSessionResponse result = gate.check(USER, "statement.csv", CONTENT, distrusted());

        assertThat(result.heldForReviewJobId()).isNull();
        verify(importJobService, never()).storeForHold(any());
    }

    @Test
    void noStorageToHoldTheFileInDiscardsTheStagedRowsAndRefuses() throws Exception {
        ApiException noStorage = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "no object storage");
        when(importJobService.storeForHold(CONTENT)).thenThrow(noStorage);
        when(importSessionService.discardUnlessUnderReview(SESSION)).thenReturn(true);

        assertThatThrownBy(() -> gate.check(USER, "statement.csv", CONTENT, distrusted())).isSameAs(noStorage);
        verify(importSessionService).discardUnlessUnderReview(SESSION);
    }

    @Test
    void aFailedHoldDiscardsTheStagedRowsAndTheStoredCopyAndRefusesWithARetryableError() throws Exception {
        when(importJobService.storeForHold(CONTENT)).thenReturn(STORED);
        when(heldStatementService.holdStagedUpload(any(), anyString(), anyString(), any(), any(), any(), any(),
                any(), anyBoolean())).thenThrow(new IllegalStateException("database unavailable"));
        when(importSessionService.discardUnlessUnderReview(SESSION)).thenReturn(true);

        assertThatThrownBy(() -> gate.check(USER, "statement.csv", CONTENT, distrusted()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(importJobService).discardStoredForHold(STORED);
        verify(importSessionService).discardUnlessUnderReview(SESSION);
    }

    @Test
    void aFailedDiscardStillRefusesTheUpload() throws Exception {
        when(importJobService.storeForHold(CONTENT)).thenThrow(new IllegalStateException("storage down"));
        when(importSessionService.discardUnlessUnderReview(SESSION)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> gate.check(USER, "statement.csv", CONTENT, distrusted()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void losingTheRaceToAnotherUploadDeletesTheUnusedCopyAndFollowsTheWinner() throws Exception {
        UUID winner = UUID.randomUUID();
        when(importJobService.storeForHold(CONTENT)).thenReturn(STORED);
        when(heldStatementService.holdStagedUpload(any(), anyString(), anyString(), any(), any(), any(), any(),
                any(), anyBoolean())).thenReturn(new HeldStatementService.StagedHold(winner, false));

        StagingSessionResponse result = gate.check(USER, "statement.csv", CONTENT, distrusted());

        assertThat(result.heldForReviewJobId()).isEqualTo(winner);
        verify(importJobService).discardStoredForHold(STORED);
        verify(verificationRecorder, never()).recordForJob(any(), any());
    }
}
