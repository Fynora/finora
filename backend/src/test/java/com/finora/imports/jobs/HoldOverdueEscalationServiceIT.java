package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.service.HeldItemAdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Gate 1 spec §4: one admin escalation per hold that passes its 48-hour promise, against real
 * PostgreSQL -- the query that finds them, the marker that makes it once, and the one-per-review
 * rule for uploads riding on another job's review. The email itself is
 * {@code HeldItemAdminAlertServiceTest}'s concern; here it is counted.
 */
class HoldOverdueEscalationServiceIT extends AbstractIntegrationTest {

    @Autowired private HoldOverdueEscalationService service;
    @Autowired private ImportJobRepository jobs;
    @Autowired private HeldStatementRepository heldStatements;
    @Autowired private UserRepository users;
    @MockitoBean private HeldItemAdminAlertService alerts;

    // Microseconds: PostgreSQL's timestamptz precision, so a marker read back compares equal.
    private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    @BeforeEach
    void freshMock() {
        reset(alerts);
    }

    private User owner() {
        User u = new User();
        u.setEmail("overdue-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Overdue Test");
        return users.save(u);
    }

    private ImportJob queued(User owner) {
        return jobs.save(new ImportJob(owner.getId(), "statement.pdf", "h-" + UUID.randomUUID(),
                "objects/" + UUID.randomUUID(), "PDF"));
    }

    private ImportJob importHold(Duration ago) {
        ImportJob job = queued(owner());
        job.holdForReview("IMPORT_NO_HEADER_DETECTED", NOW.minus(ago));
        return jobs.save(job);
    }

    private ImportJob trustHold(User owner, UUID sessionId, Duration ago) {
        ImportJob job = queued(owner);
        HeldStatement held = heldStatements.save(new HeldStatement(
                "HLD-T-" + UUID.randomUUID().toString().substring(0, 8), job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed totals disagree"));
        job.holdForTrustReview(sessionId, held.getId(), NOW.minus(ago));
        return jobs.save(job);
    }

    /** A re-upload replayed onto a session under review: held with no record of its own. */
    private ImportJob ridingHold(User owner, UUID sessionId, Duration ago) {
        ImportJob job = queued(owner);
        job.holdForTrustReview(sessionId, null, NOW.minus(ago));
        return jobs.save(job);
    }

    private ImportJob reload(ImportJob job) {
        return jobs.findById(job.getId()).orElseThrow();
    }

    @Test
    void aHoldPastFortyEightHoursIsEscalatedOnceAndMarked() {
        ImportJob job = importHold(Duration.ofHours(49));

        assertThat(service.escalate(NOW)).isEqualTo(1);

        verify(alerts).alertHoldOverdue(job.getId());
        assertThat(reload(job).getOverdueAlertedAt()).isEqualTo(NOW);
    }

    @Test
    void aSecondRunSendsNothingMore() {
        ImportJob job = importHold(Duration.ofHours(49));
        service.escalate(NOW);

        assertThat(service.escalate(NOW.plusSeconds(900))).isZero();

        verify(alerts, times(1)).alertHoldOverdue(job.getId());
    }

    @Test
    void aHoldWithinItsPromiseIsLeftAlone() {
        ImportJob job = importHold(Duration.ofHours(47));

        assertThat(service.escalate(NOW)).isZero();

        verify(alerts, never()).alertHoldOverdue(any());
        assertThat(reload(job).getOverdueAlertedAt()).isNull();
    }

    @Test
    void aHoldReEnteredAfterARerunIsEscalatedAgain() {
        ImportJob job = importHold(Duration.ofHours(100));
        service.escalate(NOW);
        ImportJob rerun = reload(job);
        rerun.returnToQueueForReprocess(NOW.minus(Duration.ofHours(50)));
        rerun.holdForReview("IMPORT_NO_HEADER_DETECTED", NOW.minus(Duration.ofHours(49)));
        jobs.save(rerun);

        assertThat(service.escalate(NOW)).isEqualTo(1);

        verify(alerts, times(2)).alertHoldOverdue(job.getId());
    }

    @Test
    void aFailingAlertStillMarksTheHoldAndDoesNotStopTheBatch() {
        ImportJob first = importHold(Duration.ofHours(60));
        ImportJob second = importHold(Duration.ofHours(55));
        doThrow(new IllegalStateException("provider down")).when(alerts).alertHoldOverdue(first.getId());

        assertThatCode(() -> service.escalate(NOW)).doesNotThrowAnyException();

        assertThat(reload(first).getOverdueAlertedAt()).as("never retried into a spam loop").isEqualTo(NOW);
        verify(alerts).alertHoldOverdue(second.getId());
        assertThat(service.escalate(NOW.plusSeconds(900))).isZero();
    }

    @Test
    void oneReviewIsEscalatedOnceNotOncePerUploadRidingOnIt() {
        User owner = owner();
        UUID session = UUID.randomUUID();
        ImportJob reviewed = trustHold(owner, session, Duration.ofHours(60));
        ImportJob riding = ridingHold(owner, session, Duration.ofHours(50));

        assertThat(service.escalate(NOW)).isEqualTo(1);

        verify(alerts).alertHoldOverdue(reviewed.getId());
        verify(alerts, never()).alertHoldOverdue(riding.getId());
        assertThat(reload(riding).getOverdueAlertedAt()).as("marked, so it is not looked at again").isEqualTo(NOW);
    }

    @Test
    void aTrustHoldWithNoRecordAndNoReviewCoveringItIsEscalated() {
        ImportJob orphan = ridingHold(owner(), UUID.randomUUID(), Duration.ofHours(49));

        assertThat(service.escalate(NOW)).isEqualTo(1);

        verify(alerts).alertHoldOverdue(orphan.getId());
    }

    @Test
    void aRidingUploadWhoseReviewWasDecidedIsNoLongerCovered() {
        // The review that covered it was rejected and the riding job somehow left held: nobody is
        // deciding it any more, so it is an orphan and must reach an admin.
        User owner = owner();
        UUID session = UUID.randomUUID();
        ImportJob reviewed = trustHold(owner, session, Duration.ofHours(60));
        HeldStatement held = heldStatements.findById(reviewed.getHeldStatementId()).orElseThrow();
        held.reject(owner.getId(), NOW.minus(Duration.ofHours(1)));
        heldStatements.save(held);
        ImportJob decided = reload(reviewed);
        decided.rejectAfterTrustReview("IMPORT_TRUST_REVIEW_REJECTED", NOW.minus(Duration.ofHours(1)));
        jobs.save(decided);
        ImportJob riding = ridingHold(owner, session, Duration.ofHours(50));

        assertThat(service.escalate(NOW)).isEqualTo(1);

        verify(alerts).alertHoldOverdue(riding.getId());
    }

    /** The dashboard counts what the escalation emails: one per review, per kind, with the oldest. */
    @Test
    void theDashboardSummaryCountsOverdueHoldsAsTheEscalationDoes() {
        User owner = owner();
        UUID session = UUID.randomUUID();
        ImportJob reviewed = trustHold(owner, session, Duration.ofHours(60));
        ridingHold(owner, session, Duration.ofHours(50));                 // covered: not counted
        ridingHold(owner(), UUID.randomUUID(), Duration.ofHours(49));     // orphan: counted
        trustHold(owner(), UUID.randomUUID(), Duration.ofHours(47));      // within the promise
        ImportJob importOverdue = importHold(Duration.ofHours(52));
        importHold(Duration.ofHours(1));
        Instant cutoff = NOW.minus(ImportJob.HOLD_PROMISE);

        ImportJobRepository.OverdueHoldSummary trust = jobs.summarizeOverdueHolds(
                ImportJob.Status.HELD_FOR_TRUST_REVIEW, cutoff, HeldStatement.Status.RESOLVED);
        ImportJobRepository.OverdueHoldSummary imports = jobs.summarizeOverdueHolds(
                ImportJob.Status.HELD_FOR_REVIEW, cutoff, HeldStatement.Status.RESOLVED);

        assertThat(trust.getCount()).isEqualTo(2);
        assertThat(trust.getOldest()).isEqualTo(reload(reviewed).getFinishedAt());
        assertThat(imports.getCount()).isEqualTo(1);
        assertThat(imports.getOldest()).isEqualTo(reload(importOverdue).getFinishedAt());
        assertThat(service.escalate(NOW)).as("the same three reach an admin").isEqualTo(3);
    }

    @Test
    void theDashboardSummaryIsZeroWithNoOldestWhenNothingIsOverdue() {
        importHold(Duration.ofHours(1));

        ImportJobRepository.OverdueHoldSummary imports = jobs.summarizeOverdueHolds(
                ImportJob.Status.HELD_FOR_REVIEW, NOW.minus(ImportJob.HOLD_PROMISE), HeldStatement.Status.RESOLVED);

        assertThat(imports.getCount()).isZero();
        assertThat(imports.getOldest()).isNull();
    }

    @Test
    void decidedJobsAreNeverEscalatedHoweverOld() {
        ImportJob job = importHold(Duration.ofHours(200));
        ImportJob resolved = reload(job);
        resolved.resolveWithoutFix(NOW.minus(Duration.ofHours(1)), "We could not read this statement.");
        jobs.save(resolved);

        assertThat(service.escalate(NOW)).isZero();

        verify(alerts, never()).alertHoldOverdue(any());
    }
}
