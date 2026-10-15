package com.finora.imports.fix;

import com.finora.config.BuildVersionResolver;
import com.finora.dto.HeldStatementRerunResultDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.imports.fix.GitHubFixChannel.PrPair;
import com.finora.imports.fix.GitHubFixChannel.PrState;
import com.finora.imports.fix.GitHubFixChannel.RunState;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldStatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FixJobFollowUpTest {

    private static final Instant SENT_AT = Instant.parse("2026-10-15T10:00:00Z");
    private static final String DEPLOYED = "d".repeat(40);

    private final Map<UUID, ClaudeFixJob> rows = new LinkedHashMap<>();
    private ClaudeFixJobRepository jobs;
    private GitHubFixChannel channel;
    private FixJobStarter starter;
    private ImportJobRepository importJobs;
    private HeldStatementRepository heldStatements;
    private AdminHeldImportService importQueue;
    private HeldStatementService trustQueue;
    private ClaudeFixProperties properties;
    private FixJobFollowUp followUp;

    @BeforeEach
    void setUp() {
        jobs = mock(ClaudeFixJobRepository.class);
        when(jobs.findByStateIn(any())).thenAnswer(inv -> rows.values().stream()
                .filter(r -> ClaudeFixJob.OPEN.contains(r.getState())).toList());
        when(jobs.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
        when(jobs.save(any(ClaudeFixJob.class))).thenAnswer(inv -> {
            ClaudeFixJob row = inv.getArgument(0);
            rows.put(row.getJobId(), row);
            return row;
        });
        channel = mock(GitHubFixChannel.class);
        starter = mock(FixJobStarter.class);
        importJobs = mock(ImportJobRepository.class);
        heldStatements = mock(HeldStatementRepository.class);
        importQueue = mock(AdminHeldImportService.class);
        trustQueue = mock(HeldStatementService.class);
        BuildVersionResolver build = mock(BuildVersionResolver.class);
        when(build.currentFullCommit()).thenReturn(DEPLOYED);
        properties = new ClaudeFixProperties();
        properties.setEnabled(true);
        followUp = new FixJobFollowUp(jobs, channel, starter, importJobs, heldStatements, importQueue, trustQueue,
                build, properties, mock(PlatformTransactionManager.class), Runnable::run);
    }

    private ImportJob job(UUID id, ImportJob.Status status) {
        ImportJob job = mock(ImportJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getStatus()).thenReturn(status);
        when(importJobs.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    private ClaudeFixJob sentRow(int attempt) {
        ClaudeFixJob row = ClaudeFixJob.sent(UUID.randomUUID(), "sha-a1", SENT_AT);
        if (attempt == 2) row.resent("sha-a2", SENT_AT);
        rows.put(row.getJobId(), row);
        return row;
    }

    private ClaudeFixJob prOpenRow() {
        ClaudeFixJob row = sentRow(1);
        row.prOpened(41, 7, SENT_AT);
        return row;
    }

    private ClaudeFixJob rereadingRow() {
        ClaudeFixJob row = prOpenRow();
        row.rereading(SENT_AT);
        return row;
    }

    private ClaudeFixJob row(ClaudeFixJob row) {
        return rows.get(row.getJobId());
    }

    // --- SENT ------------------------------------------------------------------------------------

    @Test
    void aRunInProgressChangesNothing() {
        ClaudeFixJob row = sentRow(1);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.IN_PROGRESS));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofHours(2)))).isZero();
        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.SENT);
        verifyNoInteractions(starter);
    }

    @Test
    void noRunYetUnderNinetyMinutesChangesNothing() {
        ClaudeFixJob row = sentRow(1);
        when(channel.runFor("sha-a1")).thenReturn(Optional.empty());

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofMinutes(89)))).isZero();
        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.SENT);
    }

    @Test
    void aSucceededRunWithBothPrsOpensThem() {
        ClaudeFixJob row = sentRow(1);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.SUCCEEDED));
        when(channel.prsFor(row.getJobId(), 1)).thenReturn(Optional.of(new PrPair(41, PrState.OPEN, 7)));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)))).isEqualTo(1);
        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.PR_OPEN);
        assertThat(row(row).getPublicPr()).isEqualTo(41);
        assertThat(row(row).getPrivatePr()).isEqualTo(7);
    }

    @Test
    void aFailedRunThatStillOpenedBothPrsOpensThem() {
        ClaudeFixJob row = sentRow(1);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.FAILED));
        when(channel.prsFor(row.getJobId(), 1)).thenReturn(Optional.of(new PrPair(41, PrState.OPEN, 7)));

        followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.PR_OPEN);
        verifyNoInteractions(starter);
    }

    @Test
    void aSucceededRunWithNoPrSendsAttemptTwo() {
        ClaudeFixJob row = sentRow(1);
        ImportJob job = job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.SUCCEEDED));
        when(channel.prsFor(row.getJobId(), 1)).thenReturn(Optional.empty());
        when(starter.copyAndSend(job, 2)).thenReturn(new FixJobStarter.Attempt("sha-a2", null));
        Instant now = SENT_AT.plus(Duration.ofMinutes(30));

        assertThat(followUp.followUp(now)).isEqualTo(1);
        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.SENT);
        assertThat(row(row).getAttempt()).isEqualTo(2);
        assertThat(row(row).getSentCommitSha()).isEqualTo("sha-a2");
        assertThat(row(row).getUpdatedAt()).isEqualTo(now);
        assertThat(row(row).getSentAt()).isEqualTo(SENT_AT);
    }

    @Test
    void aFailedRunSendsAttemptTwo() {
        ClaudeFixJob row = sentRow(1);
        ImportJob job = job(row.getJobId(), ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.FAILED));
        when(channel.prsFor(row.getJobId(), 1)).thenReturn(Optional.empty());
        when(starter.copyAndSend(job, 2)).thenReturn(new FixJobStarter.Attempt("sha-a2", null));

        followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)));

        assertThat(row(row).getAttempt()).isEqualTo(2);
    }

    @Test
    void noRunAfterNinetyMinutesSendsAttemptTwo() {
        ClaudeFixJob row = sentRow(1);
        ImportJob job = job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);
        when(channel.runFor("sha-a1")).thenReturn(Optional.empty());
        when(starter.copyAndSend(job, 2)).thenReturn(new FixJobStarter.Attempt("sha-a2", null));

        followUp.followUp(SENT_AT.plus(Duration.ofMinutes(90)));

        assertThat(row(row).getAttempt()).isEqualTo(2);
        verify(channel, never()).prsFor(any(), anyInt());
    }

    @Test
    void attemptTwoThatCannotBeSentGivesUp() {
        ClaudeFixJob row = sentRow(1);
        ImportJob job = job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.FAILED));
        when(channel.prsFor(row.getJobId(), 1)).thenReturn(Optional.empty());
        when(starter.copyAndSend(job, 2))
                .thenReturn(new FixJobStarter.Attempt(null, ClaudeFixJob.Reason.DIFFERS));

        followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.GAVE_UP);
        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.RUN_FAILED);
    }

    @Test
    void aSecondFailedRunGivesUpWithRunFailed() {
        ClaudeFixJob row = sentRow(2);
        when(channel.runFor("sha-a2")).thenReturn(Optional.of(RunState.FAILED));
        when(channel.prsFor(row.getJobId(), 2)).thenReturn(Optional.empty());

        followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.GAVE_UP);
        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.RUN_FAILED);
        verifyNoInteractions(starter);
    }

    @Test
    void aSecondSucceededRunWithNoPrGivesUpWithNoPr() {
        ClaudeFixJob row = sentRow(2);
        when(channel.runFor("sha-a2")).thenReturn(Optional.of(RunState.SUCCEEDED));
        when(channel.prsFor(row.getJobId(), 2)).thenReturn(Optional.empty());

        followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)));

        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.NO_PR);
    }

    @Test
    void aHoldDecidedByAPersonBeforeAttemptTwoStopsTheJob() {
        ClaudeFixJob row = sentRow(1);
        job(row.getJobId(), ImportJob.Status.COMPLETED);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.FAILED));
        when(channel.prsFor(row.getJobId(), 1)).thenReturn(Optional.empty());

        followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.GAVE_UP);
        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.DECIDED);
        verifyNoInteractions(starter);
    }

    @Test
    void aRunStillQueuedAfterThreeHoursCountsAsFailed() {
        ClaudeFixJob row = sentRow(1);
        ImportJob job = job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.IN_PROGRESS));
        when(starter.copyAndSend(job, 2)).thenReturn(new FixJobStarter.Attempt("sha-a2", null));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofMinutes(179)))).isZero();
        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofHours(3)))).isEqualTo(1);

        assertThat(row(row).getAttempt()).isEqualTo(2);
        verify(channel, never()).prsFor(any(), anyInt());
    }

    // --- catch-up --------------------------------------------------------------------------------

    @Test
    void aRecentHoldWithNoDecisionIsStartedNow() {
        UUID missed = UUID.randomUUID();
        Instant now = SENT_AT.plus(Duration.ofDays(1));
        when(jobs.findHeldWithoutDecision(now.minus(Duration.ofHours(24)), now.minus(Duration.ofMinutes(5)), 20))
                .thenReturn(List.of(missed));

        assertThat(followUp.followUp(now)).isEqualTo(1);

        verify(starter).startNow(missed);
    }

    @Test
    void aFailedCatchUpLookupStillFollowsTheOpenRows() {
        ClaudeFixJob row = sentRow(1);
        when(jobs.findHeldWithoutDecision(any(), any(), anyInt())).thenThrow(new IllegalStateException("db"));
        when(channel.runFor("sha-a1")).thenReturn(Optional.of(RunState.SUCCEEDED));
        when(channel.prsFor(row.getJobId(), 1)).thenReturn(Optional.of(new PrPair(41, PrState.OPEN, 7)));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)))).isEqualTo(1);
        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.PR_OPEN);
    }

    // --- PR_OPEN ---------------------------------------------------------------------------------

    @Test
    void anOpenPrChangesNothing() {
        ClaudeFixJob row = prOpenRow();
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.OPEN, null));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofDays(3)))).isZero();
        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.PR_OPEN);
    }

    @Test
    void aPublicPrClosedUnmergedGivesUpWithNoPr() {
        ClaudeFixJob row = prOpenRow();
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.CLOSED, null));

        followUp.followUp(SENT_AT.plus(Duration.ofDays(3)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.GAVE_UP);
        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.NO_PR);
    }

    @Test
    void noRereadBeforeTheFixIsDeployed() {
        ClaudeFixJob row = prOpenRow();
        job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.MERGED, "merge-sha"));
        when(channel.deployedContains("merge-sha", DEPLOYED)).thenReturn(false);

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofDays(1)))).isZero();

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.PR_OPEN);
        verifyNoInteractions(importQueue, trustQueue);
    }

    @Test
    void aDeployedFixRereadsAnImportHoldThroughTheQueuesReprocess() {
        ClaudeFixJob row = prOpenRow();
        job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.MERGED, "merge-sha"));
        when(channel.deployedContains("merge-sha", DEPLOYED)).thenReturn(true);

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofDays(1)))).isEqualTo(1);

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.REREADING);
        verify(importQueue).reprocess(null, row.getJobId());
        verify(channel, never()).prsFor(any(), anyInt());
    }

    @Test
    void aReprocessThatIsRefusedLeavesThePrOpenForTheNextRun() {
        ClaudeFixJob row = prOpenRow();
        job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.MERGED, "merge-sha"));
        when(channel.deployedContains("merge-sha", DEPLOYED)).thenReturn(true);
        UUID id = row.getJobId();
        when(importQueue.reprocess(null, id)).thenThrow(new ApiException(HttpStatus.CONFLICT, "live duplicate"));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofDays(1)))).isZero();

        // The transaction rolled back in production; here the row object was mutated before the
        // throw, so what matters is that the follow-up reported no change and did not throw.
        verify(importQueue).reprocess(null, id);
    }

    @Test
    void aDeployedFixRereadsATrustHoldAndAClearedReadingIsFixed() {
        ClaudeFixJob row = prOpenRow();
        job(row.getJobId(), ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        HeldStatement held = mock(HeldStatement.class);
        when(held.getHeldId()).thenReturn("HELD-00001");
        when(held.getStatus()).thenReturn(HeldStatement.Status.HELD);
        when(heldStatements.findByImportJobId(row.getJobId())).thenReturn(Optional.of(held));
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.MERGED, "merge-sha"));
        when(channel.deployedContains("merge-sha", DEPLOYED)).thenReturn(true);
        when(trustQueue.rerunParser(null, "HELD-00001"))
                .thenReturn(new HeldStatementRerunResultDto("a", "b", true, false, List.of(), null));

        followUp.followUp(SENT_AT.plus(Duration.ofDays(1)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.FIXED);
    }

    @Test
    void aDeployedFixThatLeavesATrustHoldHeldGivesUpWithStillHeld() {
        ClaudeFixJob row = prOpenRow();
        job(row.getJobId(), ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        HeldStatement held = mock(HeldStatement.class);
        when(held.getHeldId()).thenReturn("HELD-00001");
        when(held.getStatus()).thenReturn(HeldStatement.Status.HELD);
        when(heldStatements.findByImportJobId(row.getJobId())).thenReturn(Optional.of(held));
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.MERGED, "merge-sha"));
        when(channel.deployedContains("merge-sha", DEPLOYED)).thenReturn(true);
        when(trustQueue.rerunParser(null, "HELD-00001"))
                .thenReturn(new HeldStatementRerunResultDto("a", "b", true, true, List.of("still"), null));

        followUp.followUp(SENT_AT.plus(Duration.ofDays(1)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.GAVE_UP);
        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.STILL_HELD);
    }

    @Test
    void aHoldDecidedByAPersonBeforeTheDeployStopsTheJob() {
        ClaudeFixJob row = prOpenRow();
        job(row.getJobId(), ImportJob.Status.CANCELLED);
        when(channel.publicPr(41)).thenReturn(new GitHubFixChannel.PublicPr(PrState.MERGED, "merge-sha"));
        when(channel.deployedContains("merge-sha", DEPLOYED)).thenReturn(true);

        followUp.followUp(SENT_AT.plus(Duration.ofDays(1)));

        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.DECIDED);
        verifyNoInteractions(importQueue, trustQueue);
    }

    // --- REREADING -------------------------------------------------------------------------------

    @Test
    void aRereadStillProcessingChangesNothing() {
        ClaudeFixJob row = rereadingRow();
        job(row.getJobId(), ImportJob.Status.PARSING);

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofDays(1)))).isZero();
        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.REREADING);
    }

    @Test
    void aRereadThatCompletesIsFixed() {
        ClaudeFixJob row = rereadingRow();
        job(row.getJobId(), ImportJob.Status.COMPLETED);

        followUp.followUp(SENT_AT.plus(Duration.ofDays(1)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.FIXED);
    }

    @Test
    void aReheldStatementGivesUpWithStillHeldAndNoThirdAttempt() {
        ClaudeFixJob row = rereadingRow();
        job(row.getJobId(), ImportJob.Status.HELD_FOR_REVIEW);

        followUp.followUp(SENT_AT.plus(Duration.ofDays(1)));
        followUp.followUp(SENT_AT.plus(Duration.ofDays(2)));

        assertThat(row(row).getState()).isEqualTo(ClaudeFixJob.State.GAVE_UP);
        assertThat(row(row).getReason()).isEqualTo(ClaudeFixJob.Reason.STILL_HELD);
        verifyNoInteractions(starter);
        verify(channel, never()).send(any(), anyInt(), anyString(), anyString());
    }

    @Test
    void aRereadThatFailsOrIsHeldForTrustReviewIsStillHeld() {
        ClaudeFixJob failed = rereadingRow();
        job(failed.getJobId(), ImportJob.Status.FAILED);
        ClaudeFixJob trust = rereadingRow();
        job(trust.getJobId(), ImportJob.Status.HELD_FOR_TRUST_REVIEW);

        followUp.followUp(SENT_AT.plus(Duration.ofDays(1)));

        assertThat(row(failed).getReason()).isEqualTo(ClaudeFixJob.Reason.STILL_HELD);
        assertThat(row(trust).getReason()).isEqualTo(ClaudeFixJob.Reason.STILL_HELD);
    }

    // --- every run -------------------------------------------------------------------------------

    @Test
    void oneFailingRowDoesNotStopTheOthers() {
        ClaudeFixJob broken = sentRow(1);
        ClaudeFixJob fine = sentRow(1);
        when(channel.runFor("sha-a1"))
                .thenThrow(new FixChannelException("read runs", 502))
                .thenReturn(Optional.of(RunState.SUCCEEDED));
        when(channel.prsFor(fine.getJobId(), 1)).thenReturn(Optional.of(new PrPair(41, PrState.OPEN, 7)));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofMinutes(30)))).isEqualTo(1);

        assertThat(row(broken).getState()).isEqualTo(ClaudeFixJob.State.SENT);
        assertThat(row(fine).getState()).isEqualTo(ClaudeFixJob.State.PR_OPEN);
    }

    @Test
    void switchOffFollowsUpNothing() {
        properties.setEnabled(false);
        sentRow(1);

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofDays(1)))).isZero();
        followUp.scheduled();

        verifyNoInteractions(channel, starter, importQueue, trustQueue);
        verify(jobs, never()).findByStateIn(any());
        verify(jobs, never()).findHeldWithoutDecision(any(), any(), anyInt());
    }

    @Test
    void closedRowsAreNotLookedAt() {
        ClaudeFixJob fixed = rereadingRow();
        fixed.fixed(SENT_AT);
        rows.put(UUID.randomUUID(), ClaudeFixJob.notStarted(UUID.randomUUID(), ClaudeFixJob.Reason.DIFFERS, SENT_AT));

        assertThat(followUp.followUp(SENT_AT.plus(Duration.ofDays(1)))).isZero();
        verifyNoInteractions(channel);
        verify(importJobs, never()).findById(eq(fixed.getJobId()));
    }
}
