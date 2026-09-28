package com.finora.imports.refresh;

import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Statement refresh, step 5: tells a user, by push and email, that an improved parser would change
 * some of their statements. The in-app banner reads the same previews; this is what brings someone
 * who is not looking.
 *
 * <p>Once per user per parser version (the outbox key), and at most once a week: the version is the
 * build's commit, so a user who has not updated yet would otherwise be told again after every
 * deploy. Only while applying a refresh is switched on -- a notification for a button nobody can
 * press would be worse than none. Never fails the dry run that calls it.
 */
@Component
public class StatementRefreshNotifier {

    private static final Logger log = LoggerFactory.getLogger(StatementRefreshNotifier.class);

    static final Duration QUIET_PERIOD = Duration.ofDays(7);

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final boolean enabled;

    public StatementRefreshNotifier(NotificationService notificationService,
                                    NotificationRepository notificationRepository,
                                    @Value("${app.statement-refresh.apply.enabled:false}") boolean enabled) {
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.enabled = enabled;
    }

    /** A dry run found a statement of this user's that a refresh would change. */
    public void changesFound(UUID userId, String parserVersion) {
        if (!enabled) return;
        try {
            if (notificationRepository.existsByUserIdAndTypeAndCreatedAtAfter(
                    userId, NotificationType.STATEMENT_REFRESH_AVAILABLE, Instant.now().minus(QUIET_PERIOD))) {
                return;
            }
            notificationService.request(NotificationRequest.of(
                    userId,
                    NotificationType.STATEMENT_REFRESH_AVAILABLE,
                    NotificationCategory.FINANCIAL,
                    NotificationPriority.NORMAL,
                    "STATEMENT_REFRESH_" + userId + "_" + parserVersion,
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of()));
        } catch (RuntimeException e) {
            log.warn("Could not queue the statement-refresh notification for user {}: {}",
                    userId, e.getClass().getSimpleName());
        }
    }
}
