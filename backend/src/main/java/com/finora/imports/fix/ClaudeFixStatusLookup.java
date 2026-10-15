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
}
