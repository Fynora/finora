package com.finora.observability.jobs;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Background jobs page's data: every job this backend scheduled, joined with its status row,
 * its state worked out at the moment of asking (so a stuck checker cannot hide an overdue job).
 * Status rows for jobs that no longer exist are left out.
 */
@Service
public class BackgroundJobsService {

    static final Duration FAILURE_HISTORY = Duration.ofDays(30);

    private static final Map<JobState, Integer> ORDER = Map.of(
            JobState.CRASHED, 0, JobState.PARTLY_FAILED, 1, JobState.OVERDUE, 2, JobState.OFF, 3, JobState.OK, 4);

    private final JobScheduleRegistry registry;
    private final BackgroundJobStore store;
    private final Clock clock;

    @Autowired
    public BackgroundJobsService(JobScheduleRegistry registry, BackgroundJobStore store) {
        this(registry, store, Clock.systemUTC());
    }

    BackgroundJobsService(JobScheduleRegistry registry, BackgroundJobStore store, Clock clock) {
        this.registry = registry;
        this.store = store;
        this.clock = clock;
    }

    /** Broken first (crashed, partly failed, overdue), then off, then OK; by name within each. */
    public List<BackgroundJobDto> list() {
        Instant now = clock.instant();
        Map<String, BackgroundJobStatus> statuses = store.findAll().stream()
                .collect(Collectors.toMap(BackgroundJobStatus::jobKey, Function.identity()));
        return registry.all().stream()
                .map(schedule -> toDto(schedule, statuses.get(schedule.key()), now))
                .sorted(Comparator.comparing((BackgroundJobDto d) -> ORDER.get(d.state()))
                        .thenComparing(BackgroundJobDto::name))
                .toList();
    }

    public long countNeedingAttention() {
        return list().stream().filter(d -> d.state().needsAttention()).count();
    }

    /** The job's failures in the last 30 days, newest first; empty when the job does not exist. */
    public Optional<List<BackgroundJobFailure>> failures(String key) {
        if (registry.find(key).isEmpty()) return Optional.empty();
        return Optional.of(store.failuresSince(key, clock.instant().minus(FAILURE_HISTORY)));
    }

    private BackgroundJobDto toDto(JobSchedule schedule, BackgroundJobStatus status, Instant now) {
        BackgroundJobCatalog.JobInfo info = BackgroundJobCatalog.lookup(schedule.key())
                .orElse(new BackgroundJobCatalog.JobInfo(schedule.key(), ""));
        JobState state = OverdueRule.state(schedule, status, registry.appStartedAt(), now);
        return new BackgroundJobDto(schedule.key(), info.name(), info.purpose(), schedule.description(), state,
                status == null ? null : status.lastStartedAt(),
                status == null ? null : status.lastFinishedAt(),
                status == null ? null : status.lastSuccessAt(),
                status == null ? 0 : status.lastFailedItems(),
                status == null ? 0 : status.lastHandled(),
                status == null ? 0 : status.consecutiveFailures(),
                status == null ? null : status.brokenSince(),
                OverdueRule.isRunning(status));
    }
}
