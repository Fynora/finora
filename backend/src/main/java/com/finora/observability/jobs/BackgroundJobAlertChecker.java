package com.finora.observability.jobs;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.util.EmailMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every 5 minutes: works out each job's state, sends the background-job emails it calls for, and
 * deletes failure records older than 30 days.
 *
 * <p>Runs on every backend instance. Each email is claimed with a conditional update first
 * ({@link BackgroundJobStore#claimEmail}), so two instances never send the same one. Pruning always
 * runs; the emails only when {@code app.background-job-alerts.enabled} is true. This checker is a
 * recorded job too, and the page works out "overdue" itself, so a stuck checker still shows.
 */
@Component
public class BackgroundJobAlertChecker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobAlertChecker.class);
    static final String RECIPIENT_PERMISSION = "PLATFORM_DIAGNOSTICS_VIEW";

    private final JobScheduleRegistry registry;
    private final BackgroundJobStore store;
    private final UserRepository userRepository;
    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;
    private final boolean alertsEnabled;
    private final Clock clock;

    @Autowired
    public BackgroundJobAlertChecker(JobScheduleRegistry registry, BackgroundJobStore store,
                                     UserRepository userRepository, EmailProvider emailProvider,
                                     EmailProperties emailProperties,
                                     @Value("${app.background-job-alerts.enabled:false}") boolean alertsEnabled) {
        this(registry, store, userRepository, emailProvider, emailProperties, alertsEnabled, Clock.systemUTC());
    }

    BackgroundJobAlertChecker(JobScheduleRegistry registry, BackgroundJobStore store, UserRepository userRepository,
                              EmailProvider emailProvider, EmailProperties emailProperties,
                              boolean alertsEnabled, Clock clock) {
        this.registry = registry;
        this.store = store;
        this.userRepository = userRepository;
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
        this.alertsEnabled = alertsEnabled;
        this.clock = clock;
    }

    /** The same checker with emails switched on or off. */
    BackgroundJobAlertChecker withAlertsEnabled(boolean enabled) {
        return new BackgroundJobAlertChecker(registry, store, userRepository, emailProvider, emailProperties,
                enabled, clock);
    }

    @Scheduled(fixedDelayString = "${app.background-job-alerts.interval-ms:300000}",
               initialDelayString = "${app.background-job-alerts.initial-delay-ms:120000}")
    public void check() {
        Instant now = clock.instant();
        store.pruneFailuresBefore(now.minus(BackgroundJobsService.FAILURE_HISTORY));
        if (!alertsEnabled) return;

        Map<String, BackgroundJobStatus> statuses = store.findAll().stream()
                .collect(Collectors.toMap(BackgroundJobStatus::jobKey, Function.identity()));
        List<User> recipients = null;
        for (JobSchedule schedule : registry.all()) {
            try {
                BackgroundJobStatus status = statuses.get(schedule.key());
                JobState state = OverdueRule.state(schedule, status, registry.appStartedAt(), now);
                Optional<AlertKind> kind = BackgroundJobAlertDecision.decide(state, status, now);
                if (kind.isEmpty()) continue;
                String expected = status == null ? null : status.lastEmailKind();
                if (store.claimEmail(schedule.key(), expected, kind.get(), now) != 1) continue;
                if (recipients == null) {
                    recipients = userRepository.findByPermissionNameAndAccountScope(RECIPIENT_PERMISSION, User.SCOPE_ADMIN);
                }
                send(recipients, kind.get(), facts(schedule, status, state, now));
            } catch (RuntimeException e) {
                log.warn("Background job alert check failed for {}", schedule.key(), e);
                BackgroundJobReport.itemFailed(e);
            }
        }
    }

    private BackgroundJobAlertEmails.Facts facts(JobSchedule schedule, BackgroundJobStatus status, JobState state,
                                                 Instant now) {
        BackgroundJobCatalog.JobInfo info = BackgroundJobCatalog.infoFor(schedule.key());
        String error = null;
        if (state == JobState.CRASHED || state == JobState.PARTLY_FAILED) {
            error = store.failuresSince(schedule.key(), now.minus(BackgroundJobsService.FAILURE_HISTORY)).stream()
                    .findFirst().map(BackgroundJobFailure::errorSummary).orElse(null);
        }
        boolean running = OverdueRule.isRunning(status);
        Instant lastRun = status == null ? null
                : status.lastFinishedAt() != null ? status.lastFinishedAt() : status.lastStartedAt();
        Instant brokenSince = state == JobState.OVERDUE
                ? OverdueRule.overdueAt(schedule, status, registry.appStartedAt())
                : status == null ? null : status.brokenSince();
        return new BackgroundJobAlertEmails.Facts(info.name(), info.purpose(), state,
                status == null ? 0 : status.lastFailedItems(), status == null ? 0 : status.lastHandled(), error,
                brokenSince, running ? null : lastRun, running ? status.lastStartedAt() : null,
                registry.appStartedAt(), status == null ? null : status.lastSuccessAt(),
                adminBaseUrl() + "/background-jobs?job=" + java.net.URLEncoder.encode(schedule.key(), java.nio.charset.StandardCharsets.UTF_8),
                schedule.source() == JobSchedule.Source.GITHUB);
    }

    private void send(List<User> recipients, AlertKind kind, BackgroundJobAlertEmails.Facts facts) {
        if (recipients.isEmpty()) {
            log.warn("No admin holds {} -- background job email \"{}\" not sent", RECIPIENT_PERMISSION,
                    BackgroundJobAlertEmails.subject(kind, facts.name()));
            return;
        }
        String subject = BackgroundJobAlertEmails.subject(kind, facts.name());
        String html = BackgroundJobAlertEmails.html(kind, facts);
        for (User recipient : recipients) {
            try {
                EmailResult result = emailProvider.send(EmailMessage.html(recipient.getEmail(), subject, html));
                if (!result.success()) {
                    log.warn("Background job email to {} failed: {}", EmailMasking.mask(recipient.getEmail()),
                            result.failureReason());
                }
            } catch (RuntimeException e) {
                log.warn("Background job email to {} threw", EmailMasking.mask(recipient.getEmail()), e);
            }
        }
    }

    private String adminBaseUrl() {
        String base = emailProperties.getAdminAppBaseUrl();
        return base == null ? "" : base;
    }
}
