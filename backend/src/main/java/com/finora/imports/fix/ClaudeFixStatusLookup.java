package com.finora.imports.fix;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Reads a held import job's Claude fix status for the admin pages and the overdue email. */
@Component
public class ClaudeFixStatusLookup {

    private final ClaudeFixJobRepository jobs;
    private final ClaudeFixProperties properties;

    public ClaudeFixStatusLookup(ClaudeFixJobRepository jobs, ClaudeFixProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    /**
     * The job's row, if there is one. While the switch is off, a row that only says it was off, or
     * no row, reads {@code OFF}; while it is on, no row reads null (the decision is not recorded
     * yet), and a row recorded while it was off stays {@code NOT_STARTED / OFF}: "the fix was off
     * when this was held".
     */
    public ClaudeFixStatus forJob(UUID jobId) {
        int cap = properties.getMonthlyCap();
        java.util.Optional<ClaudeFixJob> row = jobs.findById(jobId);
        boolean recordedOff = row.map(r -> r.getReason() == ClaudeFixJob.Reason.OFF).orElse(false);
        if (!properties.isEnabled() && (row.isEmpty() || recordedOff)) return ClaudeFixStatus.off(cap);
        return row.map(r -> ClaudeFixStatus.of(r, cap)).orElse(null);
    }

    /**
     * How long a {@code SENT} row can go unchanged before the follow-up should have moved it on:
     * the run limit the follow-up itself waits ({@link FixJobFollowUp#RUN_LIMIT}), plus an hour for
     * its own schedule. A row older than this has stalled -- for example while GitHub cannot be read.
     */
    public static final java.time.Duration SENT_STALLED_AFTER = FixJobFollowUp.RUN_LIMIT.plusHours(1);

    /** One job's status and when its row last changed (null with no row). */
    public record Entry(ClaudeFixStatus status, java.time.Instant updatedAt) {}

    /**
     * {@link #forJob} for many jobs in one read. A job missing from the map reads as
     * {@link #forJob} would return null for it: switched on, decision not recorded yet.
     */
    public java.util.Map<UUID, Entry> forJobs(java.util.Collection<UUID> jobIds) {
        int cap = properties.getMonthlyCap();
        java.util.Map<UUID, ClaudeFixJob> rows = new java.util.HashMap<>();
        jobs.findAllById(jobIds).forEach(r -> rows.put(r.getJobId(), r));
        java.util.Map<UUID, Entry> result = new java.util.HashMap<>();
        for (UUID id : jobIds) {
            ClaudeFixJob row = rows.get(id);
            boolean recordedOff = row != null && row.getReason() == ClaudeFixJob.Reason.OFF;
            java.time.Instant updatedAt = row == null ? null : row.getUpdatedAt();
            if (!properties.isEnabled() && (row == null || recordedOff)) result.put(id, new Entry(ClaudeFixStatus.off(cap), updatedAt));
            else if (row != null) result.put(id, new Entry(ClaudeFixStatus.of(row, cap), updatedAt));
        }
        return result;
    }
}
