package com.finora.service;

import com.finora.entity.Subscription;
import com.finora.entity.SuspensionReason;
import com.finora.entity.User;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a suspension does to a Plus subscription, and which store the notice names (privacy
 * workstream 10). The email itself is queued after commit on another thread; the IT covers that path.
 */
class AccountSuspensionNoticesTest {

    private SubscriptionRepository subscriptionRepository;
    private AuditService auditService;
    private AccountSuspensionNotices notices;
    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        auditService = mock(AuditService.class);
        notices = new AccountSuspensionNotices(subscriptionRepository, mock(EmailProvider.class), auditService, Runnable::run);
    }

    private Subscription subscription(String provider, String storePlatform, boolean autoRenew) {
        Subscription s = new Subscription();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setUserId(userId);
        s.setPaymentProvider(provider);
        s.setStorePlatform(storePlatform);
        s.setAutoRenew(autoRenew);
        if ("RAZORPAY".equals(provider)) s.setRazorpaySubscriptionId("sub_synthetic_1");
        return s;
    }

    @Test
    void aWebsiteSubscription_stopsRenewing_andNamesNoStore() {
        Subscription s = subscription("RAZORPAY", null, true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(s));

        assertThat(notices.stopRenewalOrNameStore(userId, adminId)).isNull();

        assertThat(s.isAutoRenew()).isFalse();
        verify(subscriptionRepository).save(s);
        // The admin who suspended the account is the actor of this change, not the customer.
        verify(auditService).record(eq(userId), eq("SUBSCRIPTION_RENEWAL_STOPPED_FOR_SUSPENSION"), eq("Subscription"),
                eq(s.getId()), eq(java.util.Map.of("actorId", adminId.toString())));
    }

    @Test
    void aWebsiteSubscriptionAlreadyStopped_isLeftAlone() {
        Subscription s = subscription("RAZORPAY", null, false);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(s));

        assertThat(notices.stopRenewalOrNameStore(userId, adminId)).isNull();

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void anAppStoreSubscription_isNamed_andNotTouched() {
        Subscription s = subscription("REVENUECAT", "IOS", true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(s));

        assertThat(notices.stopRenewalOrNameStore(userId, adminId)).isEqualTo("the App Store");

        assertThat(s.isAutoRenew()).isTrue();
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void aGooglePlaySubscription_isNamed() {
        when(subscriptionRepository.findActiveOrTrial(userId))
                .thenReturn(Optional.of(subscription("REVENUECAT", "ANDROID", true)));

        assertThat(notices.stopRenewalOrNameStore(userId, adminId)).isEqualTo("Google Play");
    }

    /** A store subscription that will not renew again costs the customer nothing more. */
    @Test
    void aStoreSubscriptionThatWillNotRenew_isNotMentioned() {
        when(subscriptionRepository.findActiveOrTrial(userId))
                .thenReturn(Optional.of(subscription("REVENUECAT", "IOS", false)));

        assertThat(notices.stopRenewalOrNameStore(userId, adminId)).isNull();
    }

    @Test
    void noSubscription_isNothingToDo() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.empty());

        assertThat(notices.stopRenewalOrNameStore(userId, adminId)).isNull();
        verify(subscriptionRepository, never()).save(any());
    }

    /** An unverified address may belong to someone else; renewal still stops. */
    @Test
    void anUnverifiedEmail_getsNoNotice_butRenewalStillStops() {
        Subscription s = subscription("RAZORPAY", null, true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(s));
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("unverified@example.com");
        user.setEmailVerified(false);
        user.recordSuspension(SuspensionReason.FRAUD, null, Instant.now());

        notices.onSuspended(user, adminId);

        assertThat(s.isAutoRenew()).isFalse();
        verify(auditService, never()).recordEvenOnRollback(any(), eq("EMAIL_SENT"), any(), any(), any());
    }
}
