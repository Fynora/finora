package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;

/**
 * Gate 1 spec §5.2: a statement that was read, shown for review and never confirmed leaves a stamp
 * when its staged session is swept.
 *
 * <p>The October 2026 baseline found a tester whose statement was read successfully and then
 * simply vanished: the session expired after 48 hours, the sweep deleted it, and nothing recorded
 * that it had ever been waiting. "Read and abandoned at review" is the step right before a
 * statement becomes data in the app, so losing it hides exactly where activation stops.
 *
 * <p>Real PostgreSQL throughout: the stamp is a native {@code UPDATE} on a column the entity only
 * reads, in a transaction separate from the sweep's delete, and both of those are properties a
 * mock cannot have.
 */
class ExpiredUnconfirmedStampIT extends AbstractIntegrationTest {

    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportSessionRepository importSessions;
    @Autowired private ImportJobRepository importJobs;
    @Autowired private HeldStatementRepository heldStatements;
    @Autowired private UserRepository users;
    @MockitoSpyBean private StatementAnalysisSessionRepository analysisSessions;

    private User user() {
        User user = new User();
        user.setEmail("expired-unconfirmed-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Expired Unconfirmed User");
        user.setPhoneVerified(true);
        return users.save(user);
    }

    private ImportSession session(User owner, Instant expiresAt) {
        ImportSession session = importSessionService.createSession(
                owner.getId(), "statement.pdf", ("content-" + UUID.randomUUID()).getBytes(), List.of(), null);
        session.setExpiresAt(expiresAt);
        return importSessions.save(session);
    }

    private ImportSession expiredSession(User owner) {
        return session(owner, Instant.now().minus(1, ChronoUnit.HOURS));
    }

    /** The evidence row the read left behind, naming the session it staged. */
    private StatementAnalysisSession readOf(User owner, UUID sessionId) {
        String reference = "SA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return analysisSessions.save(StatementAnalysisSession.parsed(reference, owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.pdf", "PDF", 1024L, "FP-EXPIRY",
                1, 10L, 3, null, sessionId, null));
    }

    private Instant stampOf(StatementAnalysisSession read) {
        return analysisSessions.findById(read.getId()).orElseThrow().getExpiredUnconfirmedAt();
    }

    @Test
    void aStatementReadAndNeverConfirmedIsStampedWhenItsSessionIsSwept() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        StatementAnalysisSession read = readOf(owner, session.getId());
        Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);

        importSessionService.sweepExpiredSessions();

        assertThat(importSessions.findById(session.getId())).as("the session is still swept").isEmpty();
        assertThat(stampOf(read)).as("and the read remembers it was abandoned").isAfter(before);
    }

    @Test
    void aConfirmedStatementIsSweptWithoutAStamp() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        session.setStatus(ImportSession.STATUS_CONFIRMED);
        session.setConfirmedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        importSessions.save(session);
        StatementAnalysisSession read = readOf(owner, session.getId());

        importSessionService.sweepExpiredSessions();

        assertThat(importSessions.findById(session.getId())).isEmpty();
        assertThat(stampOf(read)).as("it became an import; nothing was abandoned").isNull();
    }

    @Test
    void aStatementStillWithinItsWindowIsLeftAlone() {
        User owner = user();
        ImportSession session = session(owner, Instant.now().plus(1, ChronoUnit.HOURS));
        StatementAnalysisSession read = readOf(owner, session.getId());

        importSessionService.sweepExpiredSessions();

        assertThat(importSessions.findById(session.getId())).isPresent();
        assertThat(stampOf(read)).isNull();
    }

    @Test
    void aStatementStillHeldForReviewIsNeitherSweptNorStamped() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        StatementAnalysisSession read = readOf(owner, session.getId());
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", "hash-" + UUID.randomUUID(),
                "objects/key-" + UUID.randomUUID(), "PDF");
        job.holdForTrustReview(session.getId(), null, Instant.now());
        importJobs.save(job);

        importSessionService.sweepExpiredSessions();

        assertThat(importSessions.findById(session.getId())).isPresent();
        assertThat(stampOf(read)).isNull();
    }

    /**
     * A statement whose trust review was rejected is swept unconfirmed too, but the user never had
     * the choice: the review withheld the confirm step from them. Counting it as abandoned would
     * blame the user for our decision.
     */
    @Test
    void aStatementWhoseReviewWasRejectedIsSweptButNotCountedAsAbandoned() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        StatementAnalysisSession read = readOf(owner, session.getId());
        ImportJob job = importJobs.save(new ImportJob(owner.getId(), "statement.pdf", "hash-" + UUID.randomUUID(),
                "objects/key-" + UUID.randomUUID(), "PDF"));
        HeldStatement held = heldStatements.save(new HeldStatement(
                "HLD-X-" + UUID.randomUUID().toString().substring(0, 8), job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed totals disagree"));
        job.holdForTrustReview(session.getId(), held.getId(), Instant.now().minus(3, ChronoUnit.HOURS));
        job.rejectAfterTrustReview("IMPORT_TRUST_REVIEW_REJECTED", Instant.now().minus(2, ChronoUnit.HOURS));
        importJobs.save(job);
        held.reject(owner.getId(), Instant.now().minus(2, ChronoUnit.HOURS));
        heldStatements.save(held);

        importSessionService.sweepExpiredSessions();

        assertThat(importSessions.findById(session.getId())).as("no longer under review, so swept").isEmpty();
        assertThat(stampOf(read)).as("withheld from the user, not abandoned by them").isNull();
    }

    /**
     * A Gmail-sourced session is one Fynora staged for the user, not one they uploaded. Today it has
     * no evidence row to stamp at all; this pins that it is never counted as an abandoned upload
     * even if one is ever linked to it.
     */
    @Test
    void aSessionTheUserDidNotUploadIsNeverCountedAsAnAbandonedUpload() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        session.setSource(ImportSession.SOURCE_GMAIL);
        importSessions.save(session);
        StatementAnalysisSession read = readOf(owner, session.getId());

        importSessionService.sweepExpiredSessions();

        assertThat(importSessions.findById(session.getId())).isEmpty();
        assertThat(stampOf(read)).isNull();
    }

    /** Review Focus 3: evidence must never block retention. These rows hold real statement content. */
    @Test
    void aStampThatFailsNeverStopsTheSweep() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        StatementAnalysisSession read = readOf(owner, session.getId());
        doThrow(new IllegalStateException("analysis table unreachable"))
                .when(analysisSessions).stampExpiredUnconfirmed(anyCollection(), any());

        int removed = importSessionService.sweepExpiredSessions();

        // At least this one: the sweep is platform-wide, and an earlier test's session (held then,
        // its job deleted by the base class's cleanup since) may be swept in the same run.
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(importSessions.findById(session.getId()))
                .as("the 48-hour retention is not conditional on bookkeeping").isEmpty();
        assertThat(stampOf(read)).isNull();
    }

    /** The first sweep's time stands: a later run must not move it. */
    @Test
    void aSecondSweepNeverRestampsAnEarlierAbandonment() {
        User owner = user();
        ImportSession first = expiredSession(owner);
        StatementAnalysisSession read = readOf(owner, first.getId());
        importSessionService.sweepExpiredSessions();
        Instant stamped = stampOf(read);

        // The same analysis row can only ever name one session; a direct re-stamp is the check.
        analysisSessions.stampExpiredUnconfirmed(List.of(first.getId()), Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(stampOf(read)).isEqualTo(stamped);
    }
}
