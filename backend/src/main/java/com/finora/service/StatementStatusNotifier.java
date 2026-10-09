package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;
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

    /**
     * Tells the user a statement held for trust review was rejected -- the "no" answer to the held
     * email's "We'll notify you once it's ready". Fixed copy: the reviewer's reason is internal.
     *
     * <p>Keyed on the job, so a rejection after a reopen does not tell the user a second time what
     * they were already told.
     */
    public void notifyRejected(ImportJob job) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_REJECTED,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_REJECTED_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                Map.of("jobId", job.getId().toString())));
    }

    /**
     * Tells the user what an admin decided about a held import, in the admin's own words.
     *
     * <p>Keyed on the job alone, so a double click, a retried request or a concurrent second
     * resolve collides on the outbox's unique key instead of emailing the user twice. Called inside
     * the resolve transaction, so "the job is resolved" and "the user gets told" commit together.
     *
     * @param message already validated and cleaned by the caller; substituted verbatim into the
     *                template, and HTML-escaped by the email path.
     */
    public void notifyResolved(ImportJob job, String message) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_RESOLVED,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_RESOLVED_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                Map.of("message", message, "jobId", job.getId().toString())));
    }

    /**
     * Tells the user a held import ended in failure after all -- an admin reprocessed it and the
     * reprocess failed in a way that is not held again (a curated refusal such as
     * {@code IMPORT_PAYMENT_APP_HISTORY}), or it then kept killing the worker. The user was told at
     * hold time "we'll notify you as soon as it's done", and before this nothing was sent for this
     * outcome: the import just turned up failed.
     *
     * <p>Rides {@code IMPORT_STATEMENT_RESOLVED}'s template -- "An update on your statement" around a
     * {{message}} body -- because this is the same event from the user's side, a held import closed
     * without success, and that type's push already routes to the statements screen. The body here
     * is ours rather than an admin's: the failure code's own curated message
     * ({@link ErrorCode#userSafeMessageOrNull}, the same text the job's own API returns) -- reworded
     * only for the password codes, see {@link #FAILED_AFTER_HOLD_OVERRIDES} -- or a generic sentence
     * when the failure carries no curated code.
     *
     * <p>Keyed on the job under its own prefix, so a retried pass or recovered worker collides on the
     * outbox key instead of telling the user twice. Cannot meet {@link #notifyResolved} on one job:
     * both end the job FAILED, which is terminal, and only a held job can be resolved.
     */
    public void notifyFailedAfterHold(ImportJob job) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_RESOLVED,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_FAILED_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                Map.of("message", failedAfterHoldMessage(job.getFailureCode()),
                        "jobId", job.getId().toString())));
    }

    /**
     * The two password codes' own messages assume a prompt is open in front of the user ("Enter the
     * password ..."), which is true on the upload screen and not in an email or a push. Reachable
     * here: removing every saved password also removes a held job's, so its reprocess fails asking
     * for one. Worded as both apps' failed-import card words the first ({@code
     * importFailureMessages.ts}).
     */
    private static final Map<String, String> FAILED_AFTER_HOLD_OVERRIDES = Map.of(
            ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED.name(),
            "This statement is password protected. Choose it again and enter the password your bank "
                    + "uses for it.",
            ErrorCode.IMPORT_PDF_PASSWORD_INVALID.name(),
            "The saved password did not open this statement. Choose it again and enter the password "
                    + "your bank uses for it.");

    static String failedAfterHoldMessage(String failureCode) {
        String reason = failureCode == null ? null : FAILED_AFTER_HOLD_OVERRIDES.get(failureCode);
        if (reason == null && describesTheStatement(failureCode)) {
            reason = ErrorCode.userSafeMessageOrNull(failureCode);
        }
        if (reason == null || reason.isBlank()) {
            reason = "Something went wrong on our side while reading it. Please upload it again, "
                    + "and contact support if it still doesn't work.";
        } else if (!reason.matches(".*[.!?]$")) {
            reason = reason + ".";
        }
        return "We've finished checking the statement you uploaded, but we couldn't import it. "
                + reason + " Nothing was added to your accounts.";
    }

    /**
     * Whether a stored failure code's own message is about the statement, and so reads as the
     * reason in the sentence above: the import codes. Any other code that surfaces through a parse
     * (a generic {@code INTERNAL_ERROR}'s "Unexpected error") gets the generic sentence instead, and
     * so does {@code IMPORT_SESSION_HELD_FOR_REVIEW}, whose "We'll let you know when it's ready"
     * would contradict a message saying it was not imported.
     */
    private static boolean describesTheStatement(String failureCode) {
        return failureCode != null
                && failureCode.startsWith("IMPORT_")
                && !ErrorCode.IMPORT_SESSION_HELD_FOR_REVIEW.name().equals(failureCode);
    }
}
