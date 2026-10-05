package com.finora.notification.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle, deliberately capped at what this system can observe truthfully.
 *
 * <p>DELIVERED and READ are absent on purpose: neither Resend nor 2Factor has a delivery webhook
 * wired up in this codebase, so those states could never be populated honestly and would sit
 * permanently stale. They return only once provider webhooks exist (proposal section 2.5).
 *
 * <p>SENT means the provider's synchronous API call returned success. That is the only
 * confirmation any provider gives us today.
 */
public enum NotificationStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    SENT,
    FAILED,
    RETRYING,
    DEAD_LETTER,
    /** Withdrawn before delivery: an admin cancelled a push campaign's still-queued sends. Never a
     *  failure (it is not counted as one) and never claimed by the dispatcher, which only claims
     *  CREATED, QUEUED and RETRYING rows. A row already PROCESSING cannot be cancelled. */
    CANCELLED,
    /** Nothing to deliver to: an admin push campaign reached someone whose every device is gone (the
     *  app was uninstalled, the token expired). Routine on any real send, so it is deliberately not a
     *  DEAD_LETTER: dead letters are counted and alerted on as "a user's action silently did not take
     *  effect", and every campaign would trip that alert. Only campaign pushes end this way; any
     *  other notification that cannot be delivered is still a dead letter. */
    SKIPPED;

    /** No further dispatch attempt will be made for a notification in one of these states. */
    public static final Set<NotificationStatus> TERMINAL =
            EnumSet.of(SENT, DEAD_LETTER, CANCELLED, SKIPPED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
