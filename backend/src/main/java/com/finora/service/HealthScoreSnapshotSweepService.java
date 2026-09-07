package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Fills the gap DashboardService.summarize()'s on-demand upsert leaves: a user with enough
 * transaction history to score, who simply doesn't open the dashboard in a given month, would
 * otherwise have a hole in their 6-month sparkline. Structured identically to
 * NetWorthSnapshotSweepService -- same fixedDelay/initial-delay shape, same per-user try/catch so
 * one user's failure doesn't stop the batch, same flag-gating reasoning (BH-058).
 *
 * <p>Deliberately calls the full DashboardService.summarize() per user rather than a hand-optimized
 * subset -- summarize() already IS what runs on every real dashboard load, so this sweep costs
 * nothing structurally new, just runs an already-exercised, already-tested path on a schedule
 * instead of on click. Reusing it also means the sweep's score can never disagree with what the
 * live dashboard would have shown that same user, since it's the exact same code.
 */
@Service
public class HealthScoreSnapshotSweepService {

    private static final Logger log = LoggerFactory.getLogger(HealthScoreSnapshotSweepService.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final DashboardService dashboardService;

    @Value("${app.health-score-snapshot.sweep.enabled:true}")
    private boolean sweepEnabled;

    public HealthScoreSnapshotSweepService(AccountRepository accountRepository, UserRepository userRepository,
                                            DashboardService dashboardService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.dashboardService = dashboardService;
    }

    @Scheduled(fixedDelayString = "${app.health-score-snapshot.sweep.interval-ms:14400000}",
            initialDelayString = "${app.health-score-snapshot.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        log.info("Health score snapshot sweep: {} saved, {} skipped, {} failed.",
                result.saved(), result.skipped(), result.failed());
    }

    /**
     * One sweep pass: every ACTIVE user with at least one account gets summarize() called for
     * them, which upserts their health score snapshot as a side effect when available (unavailable
     * -- too few transactions -- is not a failure, just nothing to persist this pass). One user's
     * failure is caught and does not stop the batch; that user is simply retried whole next run.
     *
     * @return how many users were saved (summarize() ran, regardless of whether a score happened
     *         to be available -- matching "attempted", not "score was available", since this
     *         service has no cheap way to know in advance which candidates will score), skipped
     *         (user not ACTIVE), or failed (summarize() threw)
     */
    public Result sweep() {
        List<UUID> candidates = accountRepository.findDistinctUserIds();
        List<User> activeUsers = userRepository.findByIdInAndStatus(candidates, User.STATUS_ACTIVE);
        int skipped = candidates.size() - activeUsers.size();

        int saved = 0;
        int failed = 0;
        for (User user : activeUsers) {
            try {
                dashboardService.summarize(user.getId());
                saved++;
            } catch (Exception e) {
                log.warn("Health score snapshot sweep failed for user {}: {}", user.getId(), e.getMessage());
                failed++;
            }
        }
        return new Result(saved, skipped, failed);
    }

    public record Result(int saved, int skipped, int failed) {}
}
