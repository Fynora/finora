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
        boolean running,
        /** BACKEND or GITHUB (part 2). */
        String source,
        /** The GitHub run's page, for GitHub rows; null otherwise. */
        String runUrl,
        /** Why a row is Off on purpose (for example a GitHub workflow waiting for a token); null otherwise. */
        String offReason
) {}
