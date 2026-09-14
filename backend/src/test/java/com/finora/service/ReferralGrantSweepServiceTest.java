package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.ReferralGrant;
import com.finora.entity.Subscription;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReferralGrantSweepServiceTest {

    private ReferralGrantRepository referralGrantRepository;
    private SubscriptionRepository subscriptionRepository;
    private PlanRepository planRepository;
    private NotificationService notificationService;
    private ReferralGrantSweepService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID freePlanId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referralGrantRepository = mock(ReferralGrantRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(PlanRepository.class);
        notificationService = mock(NotificationService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ReferralGrantSweepService(referralGrantRepository, subscriptionRepository, planRepository,
                notificationService, transactionManager);

        Plan free = new Plan();
        ReflectionTestUtils.setField(free, "id", freePlanId);
        free.setCode("FREE");
        when(planRepository.findById(freePlanId)).thenReturn(Optional.of(free));
    }

    private ReferralGrant grant(String tier, String status) {
        ReferralGrant g = new ReferralGrant();
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        g.setUserId(userId);
        g.setTier(tier);
        g.setStatus(status);
        return g;
    }

    @Test
    void sweep_activatesQueuedGrantWhenRealPlanDoesNotAlreadyCoverIt() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant pending = grant(ReferralGrant.TIER_PLUS, ReferralGrant.STATUS_PENDING);
        when(referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING)).thenReturn(List.of(userId));
        when(referralGrantRepository.findByStatusAndExpiresAtBefore(eq(ReferralGrant.STATUS_ACTIVE), any())).thenReturn(List.of());
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.empty());
        when(referralGrantRepository.findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId, ReferralGrant.STATUS_PENDING))
                .thenReturn(Optional.of(pending));

        int activated = service.sweep();

        assertThat(activated).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(ReferralGrant.STATUS_ACTIVE);
        assertThat(pending.getActivatedAt()).isNotNull();
        assertThat(pending.getExpiresAt()).isAfter(Instant.now());
        verify(referralGrantRepository).save(pending);
        verify(notificationService).request(argThat(req -> req.type() == NotificationType.REFERRAL_GRANT_ACTIVATED));
    }

    @Test
    void sweep_leavesGrantQueuedWhenUserAlreadyHasAnActiveGrant() {
        when(referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING)).thenReturn(List.of(userId));
        when(referralGrantRepository.findByStatusAndExpiresAtBefore(eq(ReferralGrant.STATUS_ACTIVE), any())).thenReturn(List.of());
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE))
                .thenReturn(Optional.of(grant(ReferralGrant.TIER_PLUS, ReferralGrant.STATUS_ACTIVE)));

        int activated = service.sweep();

        assertThat(activated).isZero();
        verify(referralGrantRepository, never()).save(any());
    }

    @Test
    void sweep_expiresGrantsPastExpiry() {
        ReferralGrant expiring = grant(ReferralGrant.TIER_PLUS, ReferralGrant.STATUS_ACTIVE);
        expiring.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING)).thenReturn(List.of());
        when(referralGrantRepository.findByStatusAndExpiresAtBefore(eq(ReferralGrant.STATUS_ACTIVE), any()))
                .thenReturn(List.of(expiring));
        when(referralGrantRepository.findById(expiring.getId())).thenReturn(Optional.of(expiring));

        service.sweep();

        assertThat(expiring.getStatus()).isEqualTo(ReferralGrant.STATUS_EXPIRED);
        verify(referralGrantRepository).save(expiring);
    }
}
