package com.finora.observability.jobs;

import java.time.Instant;

/** One row of {@code background_job_status} (V318). */
public record BackgroundJobStatus(
        String jobKey,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        JobOutcome lastOutcome,
        int lastFailedItems,
        int lastHandled,
        Instant lastSuccessAt,
        int consecutiveFailures,
        Instant brokenSince,
        String lastEmailKind,
        Instant lastEmailAt,
        Instant lastBrokenEmailAt,
        String lastInstance
) {}
