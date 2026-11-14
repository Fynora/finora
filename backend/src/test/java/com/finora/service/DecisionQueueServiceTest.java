package com.finora.service;

import com.finora.dto.DecisionQueueRowDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.fix.ClaudeFixStatus;
import com.finora.imports.fix.ClaudeFixStatusLookup;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionQueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private final ImportJobRepository jobs = mock(ImportJobRepository.class);
    private final HeldStatementRepository statements = mock(HeldStatementRepository.class);
    private final HeldStatementService heldStatementService = mock(HeldStatementService.class);
    private final ClaudeFixStatusLookup claude = mock(ClaudeFixStatusLookup.class);
    private final UserRepository users = mock(UserRepository.class);

    private final List<ImportJob> held = new ArrayList<>();
    private final List<HeldStatement> reviews = new ArrayList<>();
    private final Map<UUID, ClaudeFixStatusLookup.Entry> claudeByJob = new HashMap<>();
    private final List<User> admins = new ArrayList<>();

    private DecisionQueueService service;

    @BeforeEach
    void setUp() {
        when(jobs.findByStatusInOrderByFinishedAtAscIdAsc(anyCollection(), any())).thenAnswer(inv -> held);
        when(statements.findAllById(any())).thenAnswer(inv -> reviews);
        when(claude.forJobs(anyCollection())).thenAnswer(inv -> claudeByJob);
        when(users.findAllById(any())).thenAnswer(inv -> admins);
        when(heldStatementService.openReviewCoveringJob(any())).thenReturn(java.util.Optional.empty());
        service = new DecisionQueueService(jobs, statements, heldStatementService, claude, users,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ builders

    private ImportJob job(ImportJob.Status status, Duration heldFor) {
        ImportJob job = mock(ImportJob.class);
        UUID id = UUID.randomUUID();
        when(job.getId()).thenReturn(id);
        when(job.getStatus()).thenReturn(status);
        when(job.getFinishedAt()).thenReturn(NOW.minus(heldFor));
        when(job.getCreatedAt()).thenReturn(NOW.minus(heldFor).minusSeconds(60));
        when(job.getSourceFormat()).thenReturn("PDF");
        held.add(job);
        return job;
    }

    private ImportJob importHold(Duration heldFor) {
        return job(ImportJob.Status.HELD_FOR_REVIEW, heldFor);
    }

    private HeldStatement statementHold(Duration heldFor, HeldStatement.Status status, UUID assignee) {
        ImportJob job = job(ImportJob.Status.HELD_FOR_TRUST_REVIEW, heldFor);
        UUID jobId = job.getId();
        HeldStatement review = mock(HeldStatement.class);
        UUID reviewId = UUID.randomUUID();
        when(review.getId()).thenReturn(reviewId);
        when(review.getHeldId()).thenReturn("HLD-2026-" + reviewId.toString().substring(0, 6));
        when(review.getImportJobId()).thenReturn(jobId);
        when(review.getBankName()).thenReturn("Sample Bank");
        when(review.getStatus()).thenReturn(status);
        when(review.getAssignedEngineerId()).thenReturn(assignee);
        when(job.getHeldStatementId()).thenReturn(reviewId);
        reviews.add(review);
        return review;
    }

    private void claude(ImportJob job, String state, String reason, Integer pr) {
        claude(job, state, reason, pr, NOW.minus(Duration.ofMinutes(10)));
    }

    private void claude(ImportJob job, String state, String reason, Integer pr, Instant updatedAt) {
        claudeByJob.put(job.getId(), new ClaudeFixStatusLookup.Entry(
                new ClaudeFixStatus(state, reason, pr, null, 1, 50), updatedAt));
    }

    private void claudeOffFor(ImportJob job) {
        claudeByJob.put(job.getId(), new ClaudeFixStatusLookup.Entry(ClaudeFixStatus.off(50), null));
    }

    /** A record-less trust hold that the given review covers: a re-upload riding on it. */
    private ImportJob reuploadCoveredBy(HeldStatement review, Duration heldFor) {
        ImportJob job = job(ImportJob.Status.HELD_FOR_TRUST_REVIEW, heldFor);
        when(heldStatementService.openReviewCoveringJob(job)).thenReturn(java.util.Optional.of(review));
        return job;
    }

    private User admin(UUID id, String name) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getFullName()).thenReturn(name);
        admins.add(user);
        return user;
    }

    private DecisionQueueRowDto only() {
        List<DecisionQueueRowDto> rows = service.list(true, true);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    // ------------------------------------------------------------------ who and next step

    @Test
    void importHoldWithClaudeWorkingIsClaude() {
        ImportJob job = importHold(Duration.ofHours(2));
        claude(job, "SENT", null, null);

        DecisionQueueRowDto row = only();
        assertThat(row.kind()).isEqualTo("IMPORT");
        assertThat(row.who()).isEqualTo("CLAUDE");
        assertThat(row.nextStep()).isEqualTo("CLAUDE_WORKING");
        assertThat(row.heldAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(row.promiseAt()).isEqualTo(NOW.minus(Duration.ofHours(2)).plus(ImportJob.HOLD_PROMISE));
    }

    @Test
    void prOpenNeedsAPerson() {
        ImportJob job = importHold(Duration.ofHours(5));
        claude(job, "PR_OPEN", null, 123);

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("NOBODY");
        assertThat(row.nextStep()).isEqualTo("MERGE_PR");
        assertThat(row.prNumber()).isEqualTo(123);
    }

    @Test
    void reReadingIsClaude() {
        ImportJob job = importHold(Duration.ofHours(5));
        claude(job, "REREADING", null, 123);

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("CLAUDE");
        assertThat(row.nextStep()).isEqualTo("CLAUDE_REREADING");
    }

    @Test
    void claudeOnButUndecidedIsClaudeNotStarted() {
        importHold(Duration.ofMinutes(3)); // no entry: the lookup's "on, no decision recorded yet"

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("CLAUDE");
        assertThat(row.nextStep()).isEqualTo("CLAUDE_NOT_STARTED");
        assertThat(row.claudeFix()).isNull();
    }

    @Test
    void noClaudeRowLongAfterTheHoldMeansClaudeDidNotStart() {
        importHold(Duration.ofMinutes(16)); // past CLAUDE_START_GRACE, still no row

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("NOBODY");
        assertThat(row.nextStep()).isEqualTo("CLAUDE_DID_NOT_START");
    }

    @Test
    void noClaudeRowOnAnAssignedHoldSaysTheAdminHasIt() {
        UUID asha = UUID.randomUUID();
        statementHold(Duration.ofHours(3), HeldStatement.Status.ASSIGNED, asha);
        admin(asha, "Asha Admin");

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("ADMIN");
        assertThat(row.nextStep()).isEqualTo("ADMIN_LOOKING");
    }

    @Test
    void noClaudeRowJustInsideTheGraceIsStillClaude() {
        importHold(Duration.ofMinutes(14).plusSeconds(59));

        assertThat(only().nextStep()).isEqualTo("CLAUDE_NOT_STARTED");
    }

    @Test
    void aSentRunThatStoppedReportingIsNobodys() {
        ImportJob job = importHold(Duration.ofHours(10));
        claude(job, "SENT", null, null, NOW.minus(ClaudeFixStatusLookup.SENT_STALLED_AFTER));

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("NOBODY");
        assertThat(row.nextStep()).isEqualTo("CLAUDE_STALLED");
    }

    @Test
    void aSentRunJustUnderTheLimitIsStillWorking() {
        ImportJob job = importHold(Duration.ofHours(10));
        claude(job, "SENT", null, null, NOW.minus(ClaudeFixStatusLookup.SENT_STALLED_AFTER).plusSeconds(1));

        assertThat(only().who()).isEqualTo("CLAUDE");
    }

    @Test
    void aStepForAPersonIsNeverClaudes() {
        statementHold(Duration.ofHours(4), HeldStatement.Status.READY_FOR_IMPORT, null);
        claude(held.get(0), "SENT", null, null);

        DecisionQueueRowDto row = only();
        assertThat(row.nextStep()).isEqualTo("APPROVE_RERUN_PASSED");
        assertThat(row.who()).isEqualTo("NOBODY");
    }

    @Test
    void claudeOffIsNobody() {
        ImportJob imp = importHold(Duration.ofHours(1));
        claudeOffFor(imp);
        HeldStatement review = statementHold(Duration.ofHours(1), HeldStatement.Status.HELD, null);
        claudeOffFor(held.get(1));

        List<DecisionQueueRowDto> rows = service.list(true, true);
        assertThat(rows).extracting(DecisionQueueRowDto::who).containsOnly("NOBODY");
        assertThat(rows).filteredOn(r -> r.kind().equals("IMPORT"))
                .extracting(DecisionQueueRowDto::nextStep).containsExactly("FIX_AND_REPROCESS");
        assertThat(rows).filteredOn(r -> r.kind().equals("STATEMENT"))
                .extracting(DecisionQueueRowDto::nextStep).containsExactly("CHECK_AND_DECIDE");
        assertThat(rows).filteredOn(r -> r.kind().equals("STATEMENT"))
                .extracting(DecisionQueueRowDto::heldId).containsExactly(review.getHeldId());
    }

    @Test
    void claudeGaveUpIsNobody() {
        ImportJob job = importHold(Duration.ofHours(30));
        claude(job, "GAVE_UP", "RUN_FAILED", null);

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("NOBODY");
        assertThat(row.nextStep()).isEqualTo("FIX_AND_REPROCESS");
        assertThat(row.claudeFix().state()).isEqualTo("GAVE_UP");
    }

    @Test
    void statementReadyForImportSaysApprove() {
        statementHold(Duration.ofHours(4), HeldStatement.Status.READY_FOR_IMPORT, null);
        claude(held.get(0), "PR_OPEN", null, 77); // the re-run passing outranks the PR

        DecisionQueueRowDto row = only();
        assertThat(row.nextStep()).isEqualTo("APPROVE_RERUN_PASSED");
        assertThat(row.bank()).isEqualTo("Sample Bank");
    }

    @Test
    void assignedStatementIsAdminWithName() {
        UUID asha = UUID.randomUUID();
        statementHold(Duration.ofHours(4), HeldStatement.Status.ASSIGNED, asha);
        claudeOffFor(held.get(0));
        admin(asha, "Asha Admin");

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("ADMIN");
        assertThat(row.whoName()).isEqualTo("Asha Admin");
        assertThat(row.nextStep()).isEqualTo("ADMIN_LOOKING");
    }

    @Test
    void anAssignedAdminOutranksClaudeForWhoButClaudeStillSetsTheStep() {
        UUID asha = UUID.randomUUID();
        statementHold(Duration.ofHours(4), HeldStatement.Status.INVESTIGATING, asha);
        claude(held.get(0), "SENT", null, null);
        admin(asha, "Asha Admin");

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("ADMIN");
        assertThat(row.nextStep()).isEqualTo("CLAUDE_WORKING");
    }

    @Test
    void assigneeWithoutNameReadsAnAdmin() {
        statementHold(Duration.ofHours(4), HeldStatement.Status.ASSIGNED, UUID.randomUUID());
        claudeOffFor(held.get(0)); // and no user row for the assignee

        DecisionQueueRowDto row = only();
        assertThat(row.who()).isEqualTo("ADMIN");
        assertThat(row.whoName()).isNull();
    }

    @Test
    void noRecordHoldSaysOpenReview() {
        job(ImportJob.Status.HELD_FOR_TRUST_REVIEW, Duration.ofHours(1));

        DecisionQueueRowDto row = only();
        assertThat(row.kind()).isEqualTo("NO_RECORD");
        assertThat(row.heldId()).isNull();
        assertThat(row.nextStep()).isEqualTo("OPEN_REVIEW");
        assertThat(row.who()).isEqualTo("NOBODY");
    }

    @Test
    void coveredReuploadIsCountedOnItsReviewsRowNotListed() {
        HeldStatement review = statementHold(Duration.ofHours(2), HeldStatement.Status.HELD, null);
        claudeOffFor(held.get(0));
        reuploadCoveredBy(review, Duration.ofHours(1));

        DecisionQueueRowDto row = only();
        assertThat(row.kind()).isEqualTo("STATEMENT");
        assertThat(row.alsoDecides()).isEqualTo(1);
        assertThat(row.heldAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
    }

    /** The dashboard counts the re-upload; when it was held before its review began, the review's
     *  row must carry its clock, or the dashboard says "past the promise" with no red row here. */
    @Test
    void anEarlierCoveredReuploadSetsTheReviewsClock() {
        HeldStatement review = statementHold(Duration.ofHours(5), HeldStatement.Status.HELD, null);
        claudeOffFor(held.get(0));
        reuploadCoveredBy(review, Duration.ofHours(50));

        DecisionQueueRowDto row = only();
        assertThat(row.heldAt()).isEqualTo(NOW.minus(Duration.ofHours(50)));
        assertThat(row.promiseAt()).isBefore(NOW);
        assertThat(row.alsoDecides()).isEqualTo(1);
    }

    @Test
    void aResolvedReviewOnAStillHeldJobIsNotListed() {
        // A decision commits the review and moves the job in the same transaction; this pins that a
        // read landing between them never shows a decided hold as waiting.
        statementHold(Duration.ofHours(1), HeldStatement.Status.IMPORTED, null);

        assertThat(service.list(true, true)).isEmpty();
    }

    @Test
    void aHeldJobWithNoFinishTimeFallsBackToItsCreationTime() {
        ImportJob job = importHold(Duration.ofHours(1));
        when(job.getFinishedAt()).thenReturn(null);
        claudeOffFor(job);

        DecisionQueueRowDto row = only();
        assertThat(row.heldAt()).isEqualTo(job.getCreatedAt());
    }

    // ------------------------------------------------------------------ order and permissions

    @Test
    void nobodyFirstThenLeastTimeLeft() {
        ImportJob claudeOld = importHold(Duration.ofHours(47));   // Claude, 1 h left
        claude(claudeOld, "SENT", null, null);
        ImportJob nobodyNew = importHold(Duration.ofHours(1));    // nobody, 47 h left
        claudeOffFor(nobodyNew);
        ImportJob nobodyOverdue = importHold(Duration.ofHours(50)); // nobody, 2 h past
        claudeOffFor(nobodyOverdue);
        ImportJob claudeNew = importHold(Duration.ofHours(2));    // Claude, 46 h left
        claude(claudeNew, "REREADING", null, 5);

        assertThat(service.list(true, true)).extracting(DecisionQueueRowDto::jobId)
                .containsExactly(nobodyOverdue.getId(), nobodyNew.getId(), claudeOld.getId(), claudeNew.getId());
    }

    @Test
    void permissionsFilterKinds() {
        claudeOffFor(importHold(Duration.ofHours(1)));
        statementHold(Duration.ofHours(1), HeldStatement.Status.HELD, null);
        claudeOffFor(held.get(1));
        job(ImportJob.Status.HELD_FOR_TRUST_REVIEW, Duration.ofHours(1));

        assertThat(service.list(true, false)).extracting(DecisionQueueRowDto::kind).containsExactly("IMPORT");
        assertThat(service.list(false, true)).extracting(DecisionQueueRowDto::kind)
                .containsExactlyInAnyOrder("STATEMENT", "NO_RECORD");
        assertThat(service.list(false, false)).isEmpty();
    }
}
