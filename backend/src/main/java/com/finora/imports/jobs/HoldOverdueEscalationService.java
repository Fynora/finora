package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.repository.ImportJobRepository;
import com.finora.service.HeldItemAdminAlertService;
import com.finora.service.HeldStatementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Gate 1 spec §4: once a hold passes the 48-hour promise its user was given, the admins who can
 * decide it get one more email. One per hold entry -- {@code ImportJob.overdueAlertedAt} records
 * it, and every entry into a hold clears it -- and never a decision: nothing here releases or
 * rejects anything.
 *
 * <p>One email per review, not per upload: a re-upload replayed onto a session already under
 * review is held with no record of its own and decided by that review
 * ({@link HeldStatementService#isCoveredByOpenReview}). It is marked, so it is not looked at again,
 * but not emailed -- the review it rides on is escalated in its own right. A trust hold with no
 * record and no covering review is an orphan nobody is deciding, and is escalated.
 *
 * <p>The marker is written before the email is sent, and whether or not it succeeds: an escalation
 * is a nudge, and retrying a failing provider every run would repeat it to the admins it did reach.
 * {@link HeldItemAdminAlertService} never throws by contract; a failure here is still caught per
 * job, so one bad row cannot stop the rest of the batch.
 */
@Service
public class HoldOverdueEscalationService {

    private static final Logger log = LoggerFactory.getLogger(HoldOverdueEscalationService.class);

    /** Per run; a larger backlog drains over the following runs, oldest first. */
    static final int BATCH = 100;

    private static final Set<ImportJob.Status> HELD =
            EnumSet.of(ImportJob.Status.HELD_FOR_REVIEW, ImportJob.Status.HELD_FOR_TRUST_REVIEW);

    private enum Outcome { ESCALATE, COVERED, SKIP }

    private final ImportJobRepository jobs;
    private final HeldItemAdminAlertService alerts;
    private final HeldStatementService heldStatements;
    private final TransactionTemplate tx;
    private final Executor executor;

    @Value("${app.hold-overdue.escalation.enabled:true}")
    private boolean enabled;

    public HoldOverdueEscalationService(ImportJobRepository jobs, HeldItemAdminAlertService alerts,
                                        HeldStatementService heldStatements,
                                        PlatformTransactionManager transactionManager,
                                        @Qualifier("holdOverdueEscalationExecutor") Executor executor) {
        this.jobs = jobs;
        this.alerts = alerts;
        this.heldStatements = heldStatements;
        this.tx = new TransactionTemplate(transactionManager);
        this.executor = executor;
    }

    /**
     * Handed off, never run here: each email may take the provider's full timeout, and this thread
     * is the one the import-queue poll and the notification dispatcher also run on -- see
     * {@code BackgroundWorkConfig.holdOverdueEscalationExecutor}, which also drops a tick that
     * arrives while a run is still going.
     */
    @Scheduled(fixedDelayString = "${app.hold-overdue.escalation.interval-ms:900000}",
            initialDelayString = "${app.hold-overdue.escalation.initial-delay-ms:300000}")
    public void scheduled() {
        if (!enabled) return;
        executor.execute(() -> {
            int sent = escalate(Instant.now());
            if (sent > 0) log.info("Hold overdue escalation: {} hold(s) escalated.", sent);
        });
    }

    /** @return how many holds were escalated by email this run */
    public int escalate(Instant now) {
        List<ImportJob> due = jobs.findOverdueUnescalatedHolds(
                HELD, now.minus(ImportJob.HOLD_PROMISE), PageRequest.of(0, BATCH));
        int sent = 0;
        for (ImportJob candidate : due) {
            try {
                Outcome outcome = tx.execute(status -> mark(candidate, now));
                if (outcome == Outcome.ESCALATE) {
                    alerts.alertHoldOverdue(candidate.getId());
                    sent++;
                }
            } catch (RuntimeException e) {
                // An optimistic-lock conflict with the worker or an admin acting on the job at this
                // moment lands here too; the marker was not written, so the next run looks again.
                log.warn("Hold overdue escalation failed for import job {}; will retry next run",
                        candidate.getId(), e);
            }
        }
        return sent;
    }

    /** Re-reads the job and marks it, deciding inside the transaction whether it is still due. */
    private Outcome mark(ImportJob candidate, Instant now) {
        ImportJob job = jobs.findById(candidate.getId()).orElse(null);
        if (job == null || !job.isHoldOverdue(now) || job.getOverdueAlertedAt() != null) {
            return Outcome.SKIP;
        }
        boolean covered = job.getStatus() == ImportJob.Status.HELD_FOR_TRUST_REVIEW
                && job.getHeldStatementId() == null
                && heldStatements.isCoveredByOpenReview(job);
        job.markOverdueAlerted(now);
        jobs.save(job);
        return covered ? Outcome.COVERED : Outcome.ESCALATE;
    }
}
