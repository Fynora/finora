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
     * {@link #forJob} for many jobs in one read. A job missing from the map reads as
     * {@link #forJob} would return null for it: switched on, decision not recorded yet.
     */
    public java.util.Map<UUID, ClaudeFixStatus> forJobs(java.util.Collection<UUID> jobIds) {
        int cap = properties.getMonthlyCap();
        java.util.Map<UUID, ClaudeFixJob> rows = new java.util.HashMap<>();
        jobs.findAllById(jobIds).forEach(r -> rows.put(r.getJobId(), r));
        java.util.Map<UUID, ClaudeFixStatus> result = new java.util.HashMap<>();
        for (UUID id : jobIds) {
            ClaudeFixJob row = rows.get(id);
            boolean recordedOff = row != null && row.getReason() == ClaudeFixJob.Reason.OFF;
            if (!properties.isEnabled() && (row == null || recordedOff)) result.put(id, ClaudeFixStatus.off(cap));
            else if (row != null) result.put(id, ClaudeFixStatus.of(row, cap));
        }
        return result;
    }
}
