package com.finora.notification.campaign;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.template.RenderedMessage;
import com.finora.notification.template.TemplateRenderer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues one page of a campaign's audience into the notification outbox.
 *
 * <p>A separate path from {@code NotificationService.request}, on purpose. That method registers
 * one dispatcher nudge per call, and {@code NotificationDispatcher}'s own class comment records what
 * a burst of those did the last time something bulk-enqueued: it saturated the two-thread nudge
 * pool and silently lost delivery results. This writes a whole page in one short transaction with
 * no nudge at all; the runner nudges once when the run has finished queuing.
 *
 * <p>The words are identical for everyone, so the template is rendered once per page, not per user.
 *
 * <h2>Per person, in this order, in the page's one transaction</h2>
 * <ol>
 *   <li>Claim the person's slot for the IST day in {@link DailyCapStore}. Losing means someone else
 *       got there first: another campaign (counted as a cap skip) or this campaign earlier today (an
 *       already-queued skip -- a repeat or resumed run).
 *   <li>Insert the outbox row with {@code ON CONFLICT DO NOTHING}. The key is deterministic, so even
 *       if the cap row were gone the same person cannot be queued twice for the same campaign day.
 * </ol>
 * The claim and the row commit together or not at all: if a page fails, nobody on it lost their
 * slot without getting a row.
 */
@Component
public class CampaignEnqueuer {

    /** Prefix of every real (non-test) campaign outbox key. */
    static final String KEY_PREFIX = "PUSHCAMPAIGN_";
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final NotificationRepository notifications;
    private final TemplateRenderer templateRenderer;
    private final DailyCapStore dailyCap;

    public CampaignEnqueuer(NotificationRepository notifications, TemplateRenderer templateRenderer,
            DailyCapStore dailyCap) {
        this.notifications = notifications;
        this.templateRenderer = templateRenderer;
        this.dailyCap = dailyCap;
    }

    /** What one page did. */
    public record PageResult(int queued, int skippedCap, int skippedAlreadyQueued) {
    }

    /** {@code PUSHCAMPAIGN_{campaignId}_{yyyyMMdd}_} -- the part shared by every row of one run day. */
    public static String keyPrefix(UUID campaignId, LocalDate runDate) {
        return KEY_PREFIX + campaignId + "_" + KEY_DATE.format(runDate) + "_";
    }

    @Transactional
    public PageResult enqueuePage(UUID campaignId, String title, String message, LocalDate runDate,
            List<UUID> userIds) {
        RenderedMessage rendered = templateRenderer.render(NotificationType.CUSTOM_PUSH,
                NotificationChannel.PUSH, Map.of("title", title, "message", message));
        String prefix = keyPrefix(campaignId, runDate);
        Instant now = Instant.now();
        int queued = 0;
        int skippedCap = 0;
        int skippedAlreadyQueued = 0;
        for (UUID userId : userIds) {
            if (dailyCap.claim(userId, runDate, campaignId)) {
                String key = prefix + userId + ":" + NotificationChannel.PUSH.name();
                boolean inserted = notifications.insertIfAbsent(userId, key,
                        NotificationType.CUSTOM_PUSH.name(), NotificationCategory.FINANCIAL.name(),
                        NotificationChannel.PUSH.name(), NotificationPriority.LOW.name(),
                        rendered.title(), rendered.body(), null, now).isPresent();
                if (inserted) {
                    queued++;
                } else {
                    skippedAlreadyQueued++;
                }
            } else if (dailyCap.holder(userId, runDate).filter(campaignId::equals).isPresent()) {
                skippedAlreadyQueued++;
            } else {
                skippedCap++;
            }
        }
        return new PageResult(queued, skippedCap, skippedAlreadyQueued);
    }
}
