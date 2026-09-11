package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The single call site for "tell the user their statement's status changed" -- both PUSH and
 * EMAIL go through the existing notification outbox, giving both the outbox's own retry/backoff,
 * dead-lettering, and {@code notification_key}-based no-double-send guarantee for free, the same
 * way every other notification type already gets them. Replaces logic that used to be duplicated
 * inline at 4 call sites across {@code ImportJobWorker} and {@code HeldStatementService}.
 *
 * <p>Premium email redesign, 2026-09-11: this class originally sent the EMAIL leg directly
 * through {@link EmailProvider}, bypassing the outbox entirely, to get {@link EmailLayout}'s
 * branded wrapper and CTA button onto these two emails without waiting on the DB-template system
 * to grow rich-HTML support. That traded away the outbox's retry/backoff for a single unretried
 * send attempt, and needed a new {@code ImportJob} column to replace the idempotency guarantee it
 * also lost. Both emails now route through {@link NotificationService} like PUSH always has;
 * {@code EmailNotificationProvider} recovers {@code bank}/{@code jobId} from the persisted
 * {@code NotificationRequest.params()} (V194) to build the same rich HTML directly via
 * {@code EmailProvider.sendStatementReadyEmail}/{@code sendStatementHeldEmail} -- see that
 * interface's own doc. The held-email idempotency column this class used to maintain is gone: the
 * outbox's own {@code notification_key} dedup (this class reuses the identical deterministic key
 * on every repeat hold) already covers the EMAIL leg once it is requested through here again.
 */
@Service
public class StatementStatusNotifier {

    private final NotificationService notificationService;

    public StatementStatusNotifier(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * @param bankName the parser's own detected bank name, or the template's documented "bank"
     *                 fallback when none was detected -- the caller's responsibility, same as it
     *                 was before this class existed.
     */
    public void notifyReady(ImportJob job, String bankName) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_READY_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                // jobId backs EmailNotificationProvider's "Review Statement" deep link
                // (/app/imports/{jobId}) -- never part of the rendered {{bank}} template copy
                // itself, only of the rich HTML built around it.
                Map.of("bank", bankName, "jobId", job.getId().toString())));
    }

    public void notifyHeld(ImportJob job) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_HELD,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_HELD_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                Map.of()));
    }
}
