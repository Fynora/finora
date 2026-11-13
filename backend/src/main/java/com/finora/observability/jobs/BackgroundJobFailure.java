package com.finora.observability.jobs;

import java.time.Instant;

/** One crashed or partly failed run (V318 {@code background_job_failures}). */
public record BackgroundJobFailure(Instant occurredAt, JobOutcome outcome, int failedItems, String errorSummary) {}
