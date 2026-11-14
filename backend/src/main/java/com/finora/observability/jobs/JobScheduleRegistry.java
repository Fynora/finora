package com.finora.observability.jobs;

import com.finora.observability.jobs.github.GitHubWorkflow;
import com.finora.observability.jobs.github.GitHubWorkflowCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The jobs this backend actually scheduled, with when each is meant to run, read from Spring's own
 * scheduled-task registry. A status row whose job is not here (a job since deleted or renamed) is
 * not shown and never alerts.
 */
@Component
public class JobScheduleRegistry {

    private static final Pattern DAILY = Pattern.compile("0 (\\d{1,2}) (\\d{1,2}) \\* \\* \\*");

    private final ObjectProvider<ScheduledTaskHolder> holders;
    private volatile Instant appStartedAt = Instant.now();

    public JobScheduleRegistry(ObjectProvider<ScheduledTaskHolder> holders) {
        this.holders = holders;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        appStartedAt = Instant.now();
    }

    /** When this instance became ready; jobs are not counted late before their first chance to run. */
    public Instant appStartedAt() {
        return appStartedAt;
    }

    public List<JobSchedule> all() {
        Map<String, JobSchedule> byKey = new LinkedHashMap<>();
        holders.orderedStream().forEach(holder -> {
            for (ScheduledTask scheduled : holder.getScheduledTasks()) {
                JobSchedule schedule = scheduleOf(scheduled.getTask());
                if (schedule != null) byKey.putIfAbsent(schedule.key(), schedule);
            }
        });
        List<JobSchedule> list = new ArrayList<>(byKey.values());
        list.sort(Comparator.comparing(JobSchedule::key));
        // Part 2: the GitHub workflows the page watches, after the backend's own jobs.
        for (GitHubWorkflow w : GitHubWorkflowCatalog.all()) {
            list.add(new JobSchedule(w.key(), null, null, w.cronUtc(), describeGitHubCron(w.cronUtc()),
                    JobSchedule.Source.GITHUB, w.offReason()));
        }
        return list;
    }

    public Optional<JobSchedule> find(String key) {
        return all().stream().filter(s -> s.key().equals(key)).findFirst();
    }

    private static JobSchedule scheduleOf(Task task) {
        String key = keyFromTaskName(String.valueOf(task.getRunnable()));
        if (key == null) return null;
        if (task instanceof IntervalTask interval) {
            return new JobSchedule(key, interval.getIntervalDuration(), interval.getInitialDelayDuration(), null,
                    describeInterval(interval.getIntervalDuration()), JobSchedule.Source.BACKEND, null);
        }
        if (task instanceof CronTask cron) {
            return new JobSchedule(key, null, null, cron.getExpression(), describeCron(cron.getExpression()),
                    JobSchedule.Source.BACKEND, null);
        }
        return null;
    }

    /**
     * A scheduled method's task prints as {@code com.finora.service.AccountPurgeSweepService.scheduledSweep}
     * ({@code ScheduledMethodRunnable#toString}); the key is its last two parts.
     */
    static String keyFromTaskName(String name) {
        if (name == null) return null;
        String[] parts = name.split("\\.");
        if (parts.length < 2) return null;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    static String describeInterval(Duration interval) {
        long ms = interval.toMillis();
        if (ms % 86_400_000 == 0) return every(ms / 86_400_000, "day");
        if (ms % 3_600_000 == 0) return every(ms / 3_600_000, "hour");
        if (ms % 60_000 == 0) return every(ms / 60_000, "minute");
        if (ms % 1_000 == 0) return every(ms / 1_000, "second");
        return "every " + ms + " ms";
    }

    private static String every(long count, String unit) {
        return count == 1 ? "every " + unit : "every " + count + " " + unit + "s";
    }

    static String describeCron(String cron) {
        Matcher daily = DAILY.matcher(cron);
        if (daily.matches()) {
            return String.format("daily at %02d:%02d (server time)",
                    Integer.parseInt(daily.group(2)), Integer.parseInt(daily.group(1)));
        }
        return "cron " + cron;
    }

    private static final Pattern GITHUB_DAILY = Pattern.compile("(\\d{1,2}) (\\d{1,2}) \\* \\* \\*");
    private static final Pattern GITHUB_MONDAYS = Pattern.compile("(\\d{1,2}) (\\d{1,2}) \\* \\* 1");
    private static final Pattern GITHUB_EVERY_HOURS = Pattern.compile("0 \\*/(\\d{1,2}) \\* \\* \\*");

    /** A GitHub workflow's 5-field UTC cron in words; null means it is started by a push. */
    static String describeGitHubCron(String cron) {
        if (cron == null) return "when started by a push";
        Matcher m = GITHUB_DAILY.matcher(cron);
        if (m.matches()) return String.format("daily at %02d:%02d UTC", Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        m = GITHUB_MONDAYS.matcher(cron);
        if (m.matches()) return String.format("Mondays at %02d:%02d UTC", Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        m = GITHUB_EVERY_HOURS.matcher(cron);
        if (m.matches()) return "every " + m.group(1) + " hours";
        return "cron " + cron + " (UTC)";
    }
}
