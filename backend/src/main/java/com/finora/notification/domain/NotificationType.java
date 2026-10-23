package com.finora.notification.domain;

/**
 * Semantic trigger names. A caller names the event, never a title/body string -- copy lives in
 * notification_templates so it is reviewable in one place and reusable across channels.
 *
 * <p>Add a value here together with its notification_templates rows; a type with no template row
 * cannot render and will dead-letter.
 */
public enum NotificationType {
    PASSWORD_CHANGED,
    IMPORT_STATEMENT_READY,
    // Fired once, the first time a statement is held (parser gap or trust review) -- see
    // ImportJobWorker.recordFailure and HeldStatementService.openHold. One shared type for both
    // triggers, deliberately: the frontend's own held-state copy (importJob.ts's detail()) already
    // treats the two reasons identically from the user's point of view -- "the two states differ
    // in why we are looking, never in what the user is waiting for" -- so the notification says
    // the same thing either way, and a reprocess that fails and re-holds the same job does not
    // send a second one (same idempotency key both call sites use, "IMPORT_HELD_" + job id).
    IMPORT_STATEMENT_HELD,
    // Sent when an admin resolves a held import without fixing it -- see
    // AdminHeldImportService.resolve. The body IS the admin's message ({{message}}), so unlike every
    // other type the wording is operator-authored; the email path escapes it as text and the
    // service caps and cleans it before it gets here. Keyed "IMPORT_RESOLVED_" + job id.
    // Also sent, keyed "IMPORT_FAILED_" + job id, when a held import is reprocessed and then ends
    // FAILED (StatementStatusNotifier.notifyFailedAfterHold): the same "held import closed without
    // success" for the user, with the failure code's curated message as {{message}}.
    IMPORT_STATEMENT_RESOLVED,
    // Sent when a reviewer rejects a statement held for trust review -- see
    // HeldStatementService.reject. The trust-review counterpart of IMPORT_STATEMENT_RESOLVED, with
    // fixed copy (V257): the reviewer's reason is internal. Keyed "IMPORT_REJECTED_" + job id.
    IMPORT_STATEMENT_REJECTED,
    // Sent when a statement the user can confirm has less than a day before the session sweep
    // removes it -- see UnconfirmedImportReminder. One per user per check, however many of their
    // statements are waiting ({{waiting}} carries the count). Template rows in V269. Keyed
    // "IMPORT_CONFIRM_REMINDER_" + user id + the earliest reminded expiry.
    IMPORT_STATEMENT_CONFIRM_REMINDER,
    // Sent when an Admin or Super Admin opens the real pages of a statement held for review -- see
    // OriginalViewNotices. Tells the customer the date and that it was to fix a reading problem,
    // never who. Fixed copy, templates seeded in V272. Keyed "IMPORT_OPENED_FOR_FIX_" + job id +
    // the India date, so a second opening the same day sends nothing new.
    IMPORT_STATEMENT_OPENED_FOR_FIX,
    // Referral milestone rewards (design spec at docs/superpowers/specs/
    // 2026-09-14-referral-milestone-rewards-design.md). Template rows for all three, per channel,
    // were seeded in V207 alongside this addition -- see this enum's own class comment on why
    // that has to happen together.
    REFERRAL_FRIEND_SUBSCRIBED,
    REFERRAL_MILESTONE_REACHED,
    REFERRAL_GRANT_ACTIVATED,
    // A friend's counted payment was refunded or charged back, so that referral no longer counts
    // (ReferralService.onChargeReversed). Templates seeded in V241 alongside this addition.
    REFERRAL_REVERSED,
    /** Statement refresh, step 5: an improved parser would change some of the user's statements. */
    STATEMENT_REFRESH_AVAILABLE,
    // An admin applied a statement correction on the user's behalf -- see
    // AdminStatementCorrectionService. {{message}} is built from the correction's own counts and
    // names the account; never an amount or a narration. Templates in V284. Keyed
    // "STATEMENT_CORRECTED_" + the refresh run id, so one correction is told once.
    STATEMENT_CORRECTED_BY_SUPPORT,
    // Sent to the customer the moment they raise a support ticket -- see SupportTicketService.create.
    // EMAIL only, fixed copy seeded in V287 with {{ticketNumber}} as its one param (never the
    // ticket's subject or description, which are the customer's own words). Keyed
    // "SUPPORT_TICKET_RECEIVED_" + ticket id, so a retried create can never send a second one.
    SUPPORT_TICKET_RECEIVED,
    // An admin push campaign (com.finora.notification.campaign). Like IMPORT_STATEMENT_RESOLVED the
    // wording is operator-authored: the PUSH template (V261) is {{title}} / {{message}}. PUSH only,
    // no EMAIL row, so a request for any other channel would dead-letter -- the campaign sender
    // only ever asks for PUSH.
    CUSTOM_PUSH,
    // An admin closed the customer's ticket as a duplicate of one they raised earlier -- see
    // SupportTicketDuplicateService. EMAIL only, fixed copy seeded in V288 with two params, both
    // ticket numbers ({{ticketNumber}}, {{referenceNumber}}); never the ticket's subject or
    // description. Keyed "SUPPORT_TICKET_DUPLICATE_" + ticket id, so a ticket is only ever told once.
    SUPPORT_TICKET_DUPLICATE
}
