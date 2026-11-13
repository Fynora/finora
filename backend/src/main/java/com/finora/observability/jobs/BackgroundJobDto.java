package com.finora.observability.jobs;

import java.time.Instant;

/** One job as the Background jobs page shows it; {@code state} is worked out when asked. */
public record BackgroundJobDto(
        String key,
        String name,
        String purpose,
        String schedule,
        JobState state,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        Instant lastSuccessAt,
        int lastFailedItems,
        int lastHandled,
        int consecutiveFailures,
        Instant brokenSince,
        boolean running
) {}
