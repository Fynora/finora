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
     * The job's row, if there is one. With no row: {@code OFF} while the switch is off, and null
     * while it is on (the decision has not been recorded yet, or the hold is older than the
     * switch).
     */
    public ClaudeFixStatus forJob(UUID jobId) {
        return jobs.findById(jobId)
                .map(job -> ClaudeFixStatus.of(job, properties.getMonthlyCap()))
                .orElse(properties.isEnabled() ? null : ClaudeFixStatus.off(properties.getMonthlyCap()));
    }
}
