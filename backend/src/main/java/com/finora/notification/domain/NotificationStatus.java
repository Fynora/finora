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
    /** Nothing to deliver to: a push found no device for its user (the app was never installed, was
     *  uninstalled, or every token expired). Deliberately not a DEAD_LETTER, which is counted and
     *  alerted on as "a user's action silently did not take effect". Two kinds of push end this way:
     *  an admin campaign push, where a missing device is routine on any real send; and a push whose
     *  event also queued an email or SMS, so the user is told there (a user who only uses the web
     *  has no device at all). Any other notification that cannot be delivered is still a dead
     *  letter -- including a push whose user switched email off. */
    SKIPPED;

    /** No further dispatch attempt will be made for a notification in one of these states. */
    public static final Set<NotificationStatus> TERMINAL =
            EnumSet.of(SENT, DEAD_LETTER, CANCELLED, SKIPPED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
