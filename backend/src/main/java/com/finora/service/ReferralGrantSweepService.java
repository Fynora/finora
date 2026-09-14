package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.ReferralGrant;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Activates and expires referral-earned free-month grants (design spec at docs/superpowers/specs/
 * 2026-09-14-referral-milestone-rewards-design.md, section 3). Same shape as
 * SubscriptionCancellationDispatchSweepService: fixedDelay on a flag application-test.yml
 * disables, tests call sweep() directly, per-row TransactionTemplate rather than one transaction
 * around the whole loop -- see that class's own doc comment for the connection-pool-exhaustion
 * reasoning behind that choice, identical here.
 *
 * <p>"1 month" is implemented as a fixed 30 days, not a calendar month -- simpler and avoids
 * February/31-day edge cases the design spec never asked to be exact about.
 */
@Service
public class ReferralGrantSweepService {

    private static final Logger log = LoggerFactory.getLogger(ReferralGrantSweepService.class);

    private final ReferralGrantRepository referralGrantRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.referral-grant.sweep.enabled:true}")
    private boolean sweepEnabled;

    public ReferralGrantSweepService(ReferralGrantRepository referralGrantRepository,
                                      SubscriptionRepository subscriptionRepository,
                                      PlanRepository planRepository,
                                      NotificationService notificationService,
                                      PlatformTransactionManager transactionManager) {
        this.referralGrantRepository = referralGrantRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.notificationService = notificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.referral-grant.sweep.interval-ms:3600000}",
            initialDelayString = "${app.referral-grant.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int activated = sweep();
        if (activated > 0) {
            log.info("Referral grant sweep: {} grant(s) activated.", activated);
        }
    }

    public int sweep() {
        expireDueGrants();

        List<UUID> userIds = referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING);
        int activated = 0;
        for (UUID userId : userIds) {
            try {
                if (activateNextIfEligible(userId)) {
                    activated++;
                }
            } catch (RuntimeException e) {
                log.error("Referral grant activation failed for user {}, will retry next sweep.", userId, e);
            }
        }
        return activated;
    }

    private void expireDueGrants() {
        Instant now = Instant.now();
        List<ReferralGrant> due = referralGrantRepository.findByStatusAndExpiresAtBefore(ReferralGrant.STATUS_ACTIVE, now);
        for (ReferralGrant grant : due) {
            transactionTemplate.executeWithoutResult(status -> {
                ReferralGrant fresh = referralGrantRepository.findById(grant.getId()).orElse(null);
                if (fresh == null || !ReferralGrant.STATUS_ACTIVE.equals(fresh.getStatus())
                        || fresh.getExpiresAt() == null || fresh.getExpiresAt().isAfter(Instant.now())) {
                    return;
                }
                fresh.setStatus(ReferralGrant.STATUS_EXPIRED);
                referralGrantRepository.save(fresh);
            });
        }
    }

    /** @return true if a grant was activated for this user in this call. */
    private boolean activateNextIfEligible(UUID userId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE).isPresent()) {
                return false;
            }
            ReferralGrant next = referralGrantRepository
                    .findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId, ReferralGrant.STATUS_PENDING)
                    .orElse(null);
            if (next == null) return false;

            String realPlanCode = subscriptionRepository.findActiveOrTrial(userId)
                    .flatMap(sub -> planRepository.findById(sub.getPlanId()))
                    .map(Plan::getCode)
                    .orElse(null);
            if (ReferralGrant.tierRank(next.getTier()) <= ReferralGrant.tierRank(realPlanCode)) {
                return false;
            }

            next.setStatus(ReferralGrant.STATUS_ACTIVE);
            next.setActivatedAt(Instant.now());
            next.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
            referralGrantRepository.save(next);

            notificationService.request(NotificationRequest.of(
                    userId,
                    NotificationType.REFERRAL_GRANT_ACTIVATED,
                    NotificationCategory.FINANCIAL,
                    NotificationPriority.NORMAL,
                    "REFERRAL_GRANT_ACTIVATED_" + next.getId(),
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of("tier", next.getTier(), "expiresAt", next.getExpiresAt().toString())));
            return true;
        }));
    }
}
