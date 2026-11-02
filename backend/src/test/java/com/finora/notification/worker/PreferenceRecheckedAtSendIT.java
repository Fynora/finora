package com.finora.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.api.NotificationPreferenceResolver;
import com.finora.notification.api.NotificationPreferenceService;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.provider.ChannelSendResult;
import com.finora.notification.provider.NotificationChannelProvider;
import com.finora.notification.repository.NotificationLogRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.observability.WorkerObservability;
import com.finora.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A preference is checked again just before a notification is sent, not only when it is queued
 * (found 2026-11-02): a campaign is queued for its whole audience at once and sent at the
 * dispatcher's pace, and a failed send retries for about half an hour, so a user who switched the
 * category off, or stepped away, in between was still sent it. Real database, real
 * {@link NotificationService} and real preference resolver; only the email provider is a stand-in.
 */
class PreferenceRecheckedAtSendIT extends AbstractIntegrationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationPreferenceService preferenceService;
    @Autowired private NotificationPreferenceResolver preferenceResolver;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationLogRepository logRepository;
    @Autowired private WorkerObservability observability;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;

    private final NotificationChannelProvider email = mock(NotificationChannelProvider.class);

    @Test
    void anEmailSwitchedOffAfterItWasQueued_isWithdrawnAndNeverHandedToTheProvider() {
        UUID userId = userRepository.save(newUser()).getId();
        String requestKey = "RECHECK_" + UUID.randomUUID();
        request(userId, NotificationCategory.FINANCIAL, requestKey);
        assertThat(statusOf(requestKey)).isEqualTo(NotificationStatus.QUEUED);

        preferenceService.set(userId, NotificationCategory.FINANCIAL, NotificationChannel.EMAIL, false);
        deliverUntilSettled(requestKey);

        assertThat(statusOf(requestKey)).isEqualTo(NotificationStatus.CANCELLED);
        verify(email, never()).send(argThat(n -> n.getUserId().equals(userId)));
    }

    @Test
    void anEmailToAUserWhoDeactivatedAfterItWasQueued_isWithdrawn() {
        User user = userRepository.save(newUser());
        String requestKey = "RECHECK_" + UUID.randomUUID();
        request(user.getId(), NotificationCategory.FINANCIAL, requestKey);

        user.setStatus(User.STATUS_DEACTIVATED);
        userRepository.save(user);
        deliverUntilSettled(requestKey);

        assertThat(statusOf(requestKey)).isEqualTo(NotificationStatus.CANCELLED);
    }

    @Test
    void anEmailStillSwitchedOn_isSent() {
        UUID userId = userRepository.save(newUser()).getId();
        String requestKey = "RECHECK_" + UUID.randomUUID();
        request(userId, NotificationCategory.FINANCIAL, requestKey);

        deliverUntilSettled(requestKey);

        assertThat(statusOf(requestKey)).isEqualTo(NotificationStatus.SENT);
    }

    /** Security notices cannot be switched off, so stepping away after queuing does not stop one. */
    @Test
    void aSecurityEmail_isSentEvenAfterTheUserDeactivated() {
        User user = userRepository.save(newUser());
        String requestKey = "RECHECK_" + UUID.randomUUID();
        request(user.getId(), NotificationCategory.SECURITY, requestKey);

        user.setStatus(User.STATUS_DEACTIVATED);
        userRepository.save(user);
        deliverUntilSettled(requestKey);

        assertThat(statusOf(requestKey)).isEqualTo(NotificationStatus.SENT);
    }

    private void request(UUID userId, NotificationCategory category, String requestKey) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notificationService.request(NotificationRequest.of(userId,
                        NotificationType.IMPORT_STATEMENT_HELD, category, NotificationPriority.NORMAL,
                        requestKey, Set.of(NotificationChannel.EMAIL), Map.of())));
    }

    /** The queue is shared with rows other tests left behind, so one pass of 50 may not reach this
     *  row; bounded so a regression fails instead of hanging. */
    private void deliverUntilSettled(String requestKey) {
        when(email.channel()).thenReturn(NotificationChannel.EMAIL);
        when(email.isConfigured()).thenReturn(true);
        when(email.send(any())).thenReturn(ChannelSendResult.success("resend", "ok"));
        NotificationDispatcher dispatcher = new NotificationDispatcher(notificationRepository,
                logRepository, List.of(email), observability, transactionManager, preferenceResolver);
        for (int pass = 0; pass < 20; pass++) {
            if (statusOf(requestKey).isTerminal() || dispatcher.drainOnce() == 0) {
                break;
            }
        }
    }

    private NotificationStatus statusOf(String requestKey) {
        String key = requestKey + ":" + NotificationChannel.EMAIL.name();
        return notificationRepository.findByNotificationKey(key).map(Notification::getStatus)
                .orElseThrow(() -> new AssertionError("no notification row for " + key));
    }

    private User newUser() {
        User user = new User();
        user.setEmail("pref-recheck-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Preference Recheck IT User");
        user.setPhoneVerified(true);
        return user;
    }
}
