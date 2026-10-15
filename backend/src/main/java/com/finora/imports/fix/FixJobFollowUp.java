package com.finora.imports.fix;

import com.finora.config.BuildVersionResolver;
import com.finora.dto.HeldStatementRerunResultDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.imports.fix.GitHubFixChannel.PrPair;
import com.finora.imports.fix.GitHubFixChannel.RunState;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldStatementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Follows each open Claude fix job by polling GitHub, outbound only (docs/superpowers/specs/
 * 2026-10-15-claude-fix-job-design.md, section 4): opens the job's PRs on the admin page, sends the
 * second and last attempt or gives up, and re-reads the statement once the merged fix is deployed.
 *
 * <p>Per open row: the GitHub reads happen first, outside any transaction, so no database
 * connection waits on GitHub; the change is then made in a short transaction of its own. A row
 * that fails is logged with its job id and the exception class and left for the next run; it never
 * stops the others. A person deciding the hold meanwhile stops the job ({@code DECIDED}).
 *
 * <p>The re-read uses the queues' own re-run with no actor. An import hold is requeued, and the
 * next run reads where the worker took it. A trust hold's re-run answers at once: cleared means it
 * waits, ready, for a person to approve, as any re-run that clears does; still held means the fix
 * did not do it.
 */
@Service
public class FixJobFollowUp {

    private static final Logger log = LoggerFactory.getLogger(FixJobFollowUp.class);

    /** How long a sent attempt may show no run before it counts as failed. */
    static final Duration RUN_GRACE = Duration.ofMinutes(90);
    /** How long a run may show as queued or in progress before it counts as failed: the workflow
     *  stops itself well before this, so a run still not finished is stuck (no runner, no minutes). */
    static final Duration RUN_LIMIT = Duration.ofHours(3);
    /** How far back the catch-up looks for a hold with no decision, and how long it leaves a fresh
     *  hold to its own after-commit start (which also waits for the hold's findings). */
    static final Duration CATCH_UP_WINDOW = Duration.ofHours(24);
    static final Duration CATCH_UP_DELAY = Duration.ofMinutes(5);
    static final int CATCH_UP_BATCH = 20;

    private final ClaudeFixJobRepository jobs;
    private final GitHubFixChannel channel;
    private final FixJobStarter starter;
    private final ImportJobRepository importJobs;
    private final HeldStatementRepository heldStatements;
    private final AdminHeldImportService importQueue;
    private final HeldStatementService trustQueue;
    private final BuildVersionResolver build;
    private final ClaudeFixProperties properties;
    private final TransactionTemplate tx;
    private final Executor executor;

    public FixJobFollowUp(ClaudeFixJobRepository jobs, GitHubFixChannel channel, FixJobStarter starter,
                          ImportJobRepository importJobs, HeldStatementRepository heldStatements,
                          AdminHeldImportService importQueue, HeldStatementService trustQueue,
                          BuildVersionResolver build, ClaudeFixProperties properties,
                          PlatformTransactionManager transactionManager,
                          @Qualifier("claudeFixExecutor") Executor executor) {
        this.jobs = jobs;
        this.channel = channel;
        this.starter = starter;
        this.importJobs = importJobs;
        this.heldStatements = heldStatements;
        this.importQueue = importQueue;
        this.trustQueue = trustQueue;
        this.build = build;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.executor = executor;
    }

    /** Handed to the fix job's own thread: every GitHub call may take its full timeout. */
    @Scheduled(fixedDelayString = "${app.claude-fix.follow-up-interval-ms:600000}",
            initialDelayString = "${app.claude-fix.follow-up-initial-delay-ms:120000}")
    public void scheduled() {
        if (!properties.isEnabled()) return;
        executor.execute(() -> {
            int changed = followUp(Instant.now());
            if (changed > 0) log.info("Claude fix follow-up: {} job(s) moved on", changed);
        });
    }

    /** @return how many rows changed, holds caught up included */
    public int followUp(Instant now) {
        if (!properties.isEnabled()) return 0;
        int changed = catchUp(now);
        for (ClaudeFixJob row : jobs.findByStateIn(ClaudeFixJob.OPEN)) {
            try {
                if (step(row, now)) changed++;
            } catch (RuntimeException e) {
                log.warn("Claude fix follow-up for held job {} failed ({}); will retry next run", row.getJobId(),
                        FixJobStarter.typeName(e));
            }
        }
        return changed;
    }

    /**
     * Starts the decision for recent holds that have none: their after-commit start was dropped
     * (the fix job's queue was full) or lost (the server restarted before it ran). Without this
     * such a hold would show nothing and never be tried.
     */
    private int catchUp(Instant now) {
        List<UUID> missed;
        try {
            missed = jobs.findHeldWithoutDecision(now.minus(CATCH_UP_WINDOW), now.minus(CATCH_UP_DELAY), CATCH_UP_BATCH);
        } catch (RuntimeException e) {
            log.warn("Claude fix catch-up could not look for missed holds ({})", FixJobStarter.typeName(e));
            return 0;
        }
        for (UUID jobId : missed) {
            log.info("Claude fix for held job {}: its start never ran; starting it now", jobId);
            starter.startNow(jobId);
        }
        return missed.size();
    }

    private boolean step(ClaudeFixJob row, Instant now) {
        return switch (row.getState()) {
            case SENT -> sent(row, now);
            case PR_OPEN -> prOpen(row, now);
            case REREADING -> rereading(row, now);
            default -> false;
        };
    }

    private boolean sent(ClaudeFixJob row, Instant now) {
        UUID id = row.getJobId();
        Optional<RunState> run = channel.runFor(row.getSentCommitSha());
        Duration sinceSent = Duration.between(row.getUpdatedAt(), now);
        if (run.isPresent() && run.get() == RunState.IN_PROGRESS && sinceSent.compareTo(RUN_LIMIT) < 0) return false;
        if (run.isEmpty() && sinceSent.compareTo(RUN_GRACE) < 0) return false;
        if (run.isPresent() && run.get() != RunState.IN_PROGRESS) {
            // A run that failed after opening both PRs (say, in its clean-up step) still opened them.
            Optional<PrPair> prs = channel.prsFor(id, row.getAttempt());
            if (prs.isPresent()) {
                return change(id, r -> r.prOpened(prs.get().publicPr(), prs.get().privatePr(), now));
            }
        }
        ClaudeFixJob.Reason failure = run.isPresent() && run.get() == RunState.SUCCEEDED
                ? ClaudeFixJob.Reason.NO_PR : ClaudeFixJob.Reason.RUN_FAILED;
        if (row.getAttempt() >= ClaudeFixJob.MAX_ATTEMPTS) return change(id, r -> r.gaveUp(failure, now));
        Optional<ImportJob> job = importJobs.findById(id).filter(FixJobStarter::isHeld);
        if (job.isEmpty()) return change(id, r -> r.gaveUp(ClaudeFixJob.Reason.DECIDED, now));
        FixJobStarter.Attempt second = starter.copyAndSend(job.get(), 2);
        log.info("Claude fix for held job {}: attempt 1 {}; {}", id, failure,
                second.wasSent() ? "attempt 2 sent" : "attempt 2 not sent (" + second.notSentReason() + ")");
        return change(id, r -> {
            if (second.wasSent()) {
                r.resent(second.commitSha(), now);
            } else {
                r.gaveUp(failure, now);
            }
        });
    }

    private boolean prOpen(ClaudeFixJob row, Instant now) {
        UUID id = row.getJobId();
        // By the PR's number, not its branch: the branch is deleted once the PR is merged or closed.
        GitHubFixChannel.PublicPr pr = channel.publicPr(row.getPublicPr());
        switch (pr.state()) {
            case OPEN -> {
                return false;
            }
            case CLOSED -> {
                return change(id, r -> r.gaveUp(ClaudeFixJob.Reason.NO_PR, now));
            }
            default -> {
                // MERGED: below.
            }
        }
        if (pr.mergeSha() == null || !channel.deployedContains(pr.mergeSha(), build.currentFullCommit())) return false;

        Optional<ImportJob> job = importJobs.findById(id);
        if (job.isEmpty() || !FixJobStarter.isHeld(job.get())) {
            return change(id, r -> r.gaveUp(ClaudeFixJob.Reason.DECIDED, now));
        }
        if (job.get().getStatus() == ImportJob.Status.HELD_FOR_REVIEW) {
            // Requeued in the same transaction as the row's move, so a refused requeue (a live
            // duplicate, say) leaves the row PR_OPEN for the next run.
            log.info("Claude fix for held job {}: fix deployed, requeueing the statement", id);
            return Boolean.TRUE.equals(tx.execute(status -> {
                ClaudeFixJob r = jobs.findById(id).orElseThrow();
                r.rereading(now);
                jobs.save(r);
                importQueue.reprocess(null, id);
                return true;
            }));
        }
        Optional<HeldStatement> held = heldStatements.findByImportJobId(id);
        if (held.isEmpty()) return false;
        if (held.get().getStatus().isResolved()) return change(id, r -> r.gaveUp(ClaudeFixJob.Reason.DECIDED, now));
        String heldId = held.get().getHeldId();
        log.info("Claude fix for held job {}: fix deployed, re-reading the trust hold", id);
        return Boolean.TRUE.equals(tx.execute(status -> {
            ClaudeFixJob r = jobs.findById(id).orElseThrow();
            r.rereading(now);
            HeldStatementRerunResultDto result = trustQueue.rerunParser(null, heldId);
            if (result.stillHeld()) {
                r.gaveUp(ClaudeFixJob.Reason.STILL_HELD, now);
            } else {
                r.fixed(now);
            }
            jobs.save(r);
            return true;
        }));
    }

    /** Only an import hold is ever here: a trust hold's re-run answers at once. */
    private boolean rereading(ClaudeFixJob row, Instant now) {
        UUID id = row.getJobId();
        Optional<ImportJob> job = importJobs.findById(id);
        if (job.isEmpty()) return change(id, r -> r.gaveUp(ClaudeFixJob.Reason.DECIDED, now));
        return switch (job.get().getStatus()) {
            case COMPLETED -> change(id, r -> r.fixed(now));
            case HELD_FOR_REVIEW, HELD_FOR_TRUST_REVIEW, FAILED ->
                    change(id, r -> r.gaveUp(ClaudeFixJob.Reason.STILL_HELD, now));
            case CANCELLED -> change(id, r -> r.gaveUp(ClaudeFixJob.Reason.DECIDED, now));
            default -> false;
        };
    }

    private boolean change(UUID id, Consumer<ClaudeFixJob> change) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            ClaudeFixJob r = jobs.findById(id).orElse(null);
            if (r == null) return false;
            change.accept(r);
            jobs.save(r);
            return true;
        }));
    }
}
