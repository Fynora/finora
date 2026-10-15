package com.finora.imports.fix;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The automatic Claude fix decision for one held import job (V274). Holds references, states and PR
 * numbers only, never statement text. See docs/superpowers/specs/2026-10-15-claude-fix-job-design.md.
 *
 * <p>The id is the import job's own, assigned rather than generated, so the entity says itself
 * whether it is new ({@link Persistable}): without that, saving a second new row for the same job
 * would quietly merge into the first instead of being refused by the primary key, and the "one job
 * per held item" rule would rest on a read-before-write alone.
 */
@Entity
@Table(name = "claude_fix_jobs")
public class ClaudeFixJob implements Persistable<UUID> {

    public enum State { NOT_STARTED, SENT, PR_OPEN, REREADING, FIXED, GAVE_UP }

    /** Why no job started ({@code OFF} to {@code ERROR}), or why one gave up (the last four).
     *  {@code DECIDED}: a person decided the hold while the job was open, so it stopped. */
    public enum Reason { OFF, CSV, DIFFERS, NOT_CHECKED, REFUSED, CAP_REACHED, ERROR, RUN_FAILED, NO_PR, STILL_HELD, DECIDED }

    /** The states the follow-up still looks at. */
    public static final Set<State> OPEN = Set.of(State.SENT, State.PR_OPEN, State.REREADING);

    public static final int MAX_ATTEMPTS = 2;

    @Id @Column(name = "job_id") private UUID jobId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private State state;
    @Enumerated(EnumType.STRING) private Reason reason;
    @Column(nullable = false) private int attempt;
    @Column(name = "sent_commit_sha") private String sentCommitSha;
    @Column(name = "public_pr") private Integer publicPr;
    @Column(name = "private_pr") private Integer privatePr;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @Transient private boolean isNew;

    protected ClaudeFixJob() {}

    private ClaudeFixJob(UUID jobId, State state, Instant now) {
        this.jobId = jobId;
        this.state = state;
        this.createdAt = now;
        this.updatedAt = now;
        this.isNew = true;
    }

    public static ClaudeFixJob notStarted(UUID jobId, Reason reason, Instant now) {
        ClaudeFixJob job = new ClaudeFixJob(jobId, State.NOT_STARTED, now);
        job.reason = reason;
        return job;
    }

    public static ClaudeFixJob sent(UUID jobId, String commitSha, Instant now) {
        ClaudeFixJob job = new ClaudeFixJob(jobId, State.SENT, now);
        job.attempt = 1;
        job.sentCommitSha = commitSha;
        job.sentAt = now;
        return job;
    }

    /** The run opened its two PRs. */
    public void prOpened(int publicPr, int privatePr, Instant now) {
        require(state == State.SENT, "prOpened");
        this.publicPr = publicPr;
        this.privatePr = privatePr;
        move(State.PR_OPEN, now);
    }

    /** The second and last attempt was sent. {@code sentAt} keeps the first send: the monthly cap
     *  counts jobs started, not attempts. */
    public void resent(String commitSha, Instant now) {
        require(state == State.SENT && attempt == 1, "resent");
        this.attempt = 2;
        this.sentCommitSha = commitSha;
        move(State.SENT, now);
    }

    public void gaveUp(Reason why, Instant now) {
        require(OPEN.contains(state), "gaveUp");
        this.reason = why;
        move(State.GAVE_UP, now);
    }

    /** The public PR is merged and deployed; the statement is being read again. */
    public void rereading(Instant now) {
        require(state == State.PR_OPEN, "rereading");
        move(State.REREADING, now);
    }

    public void fixed(Instant now) {
        require(state == State.REREADING, "fixed");
        move(State.FIXED, now);
    }

    private void move(State to, Instant now) {
        this.state = to;
        this.updatedAt = now;
    }

    private void require(boolean legal, String transition) {
        if (!legal) {
            throw new IllegalStateException("Claude fix job " + jobId + ": " + transition + " is not allowed from "
                    + state + " (attempt " + attempt + ")");
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override public UUID getId() { return jobId; }
    @Override public boolean isNew() { return isNew; }

    public UUID getJobId() { return jobId; }
    public State getState() { return state; }
    public Reason getReason() { return reason; }
    public int getAttempt() { return attempt; }
    public String getSentCommitSha() { return sentCommitSha; }
    public Integer getPublicPr() { return publicPr; }
    public Integer getPrivatePr() { return privatePr; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
