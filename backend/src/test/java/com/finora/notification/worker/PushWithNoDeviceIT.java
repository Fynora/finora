package com.finora.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.api.DeviceTokenService;
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
import com.finora.notification.provider.FcmMessageSender;
import com.finora.notification.provider.FcmPushProvider;
import com.finora.notification.provider.NotificationChannelProvider;
import com.finora.notification.repository.NotificationLogRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.observability.PushTokenRevocations;
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
 * A push to a user with no registered device, end to end against the real database: the rows are
 * written by the real {@link NotificationService} with the exact request a held statement makes
 * ({@code StatementStatusNotifier.notifyHeld}), and delivered by a dispatcher holding the real
 * {@link FcmPushProvider}, which looks the user's devices up through the real
 * {@link DeviceTokenService}. Only the network edges are stand-ins: FCM itself (never reached, the
 * user has no token) and the email provider.
 *
 * <p>Production, 2026-10-21: a user who only uses the web had three statements held; each email
 * was sent and each push dead-lettered into a Sentry error.
 */
class PushWithNoDeviceIT extends AbstractIntegrationTest {

    @Autowired private NotificationService notificationService;
    @Autowired private NotificationPreferenceService preferenceService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationLogRepository logRepository;
    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private PushTokenRevocations revocations;
    @Autowired private WorkerObservability observability;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;

    @Test
    void aHeldStatementPushToAWebOnlyUserIsSkipped_becauseTheEmailWentOut() {
        UUID userId = userRepository.save(newUser()).getId();
        String requestKey = "IMPORT_HELD_" + UUID.randomUUID();

        requestHeldNotice(userId, requestKey);
        deliverUntilSettled(requestKey);

        assertThat(statusOf(requestKey, NotificationChannel.EMAIL)).isEqualTo(NotificationStatus.SENT);
        Notification push = row(requestKey, NotificationChannel.PUSH);
        assertThat(push.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(push.getLastError()).isEqualTo("no registered device");
    }

    @Test
    void theSamePushIsSkipped_evenWhenTheUserSwitchedStatementEmailsOff() {
        // Owner's decision, 2026-10-21: no phone is routine, whether or not an email also went out.
        UUID userId = userRepository.save(newUser()).getId();
        preferenceService.set(userId, NotificationCategory.FINANCIAL, NotificationChannel.EMAIL, false);
        String requestKey = "IMPORT_HELD_" + UUID.randomUUID();

        requestHeldNotice(userId, requestKey);
        deliverUntilSettled(requestKey);

        assertThat(notificationRepository.findByNotificationKey(key(requestKey, NotificationChannel.EMAIL)))
                .isEmpty();
        assertThat(statusOf(requestKey, NotificationChannel.PUSH)).isEqualTo(NotificationStatus.SKIPPED);
    }

    /** The exact request {@code StatementStatusNotifier.notifyHeld} makes, in a transaction as there. */
    private void requestHeldNotice(UUID userId, String requestKey) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notificationService.request(NotificationRequest.of(userId,
                        NotificationType.IMPORT_STATEMENT_HELD, NotificationCategory.FINANCIAL,
                        NotificationPriority.NORMAL, requestKey,
                        Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL), Map.of())));
    }

    /**
     * Drains the shared queue until this test's push is terminal. The queue is shared with rows
     * other tests left behind, so one pass of 50 may not reach these; bounded so a regression fails
     * instead of hanging.
     */
    private void deliverUntilSettled(String requestKey) {
        NotificationChannelProvider email = mock(NotificationChannelProvider.class);
        when(email.channel()).thenReturn(NotificationChannel.EMAIL);
        when(email.isConfigured()).thenReturn(true);
        when(email.send(any())).thenReturn(ChannelSendResult.success("resend", "ok"));
        FcmPushProvider push = new FcmPushProvider(deviceTokenService, mock(FcmMessageSender.class),
                revocations);
        NotificationDispatcher dispatcher = new NotificationDispatcher(notificationRepository,
                logRepository, List.of(push, email), observability, transactionManager);

        for (int pass = 0; pass < 20; pass++) {
            if (statusOf(requestKey, NotificationChannel.PUSH).isTerminal()
                    || dispatcher.drainOnce() == 0) {
                break;
            }
        }
    }

    private NotificationStatus statusOf(String requestKey, NotificationChannel channel) {
        return row(requestKey, channel).getStatus();
    }

    private Notification row(String requestKey, NotificationChannel channel) {
        String key = key(requestKey, channel);
        return notificationRepository.findByNotificationKey(key)
                .orElseThrow(() -> new AssertionError("no notification row for " + key));
    }

    /** How {@code NotificationService.request} stores each channel's row (short keys are not truncated). */
    private static String key(String requestKey, NotificationChannel channel) {
        return requestKey + ":" + channel.name();
    }

    private User newUser() {
        User user = new User();
        user.setEmail("push-no-device-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Push No Device IT User");
        user.setPhoneVerified(true);
        return user;
    }
}
