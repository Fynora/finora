package com.finora.notification.provider;

/**
 * Outcome of one FCM send attempt for a single device token.
 *
 * <p>Deliberately not a boolean: a boolean can only say "worked" or "didn't," and that collapses
 * two operationally very different failures into one. FCM distinguishes a token that will never
 * work again (the app was uninstalled, the token expired or was rotated) from an ordinary
 * transient failure (a momentary FCM outage, a rate limit) -- the former should never be retried
 * and should stop being tried at all ({@link com.finora.notification.api.DeviceTokenService#revoke}),
 * the latter deserves exactly the dispatcher's existing retry/backoff and nothing more. Collapsing
 * that distinction is what let dead tokens accumulate silently: nothing on the send path could
 * previously tell "this token is gone for good" apart from "FCM had a bad moment," so nothing ever
 * called revoke.
 *
 * <p>No outcome here carries a message or the token itself -- see {@link FcmMessageSender#send}'s
 * own doc for why that absence is load-bearing, not incidental.
 */
public enum FcmSendOutcome {
    /** FCM accepted the message for delivery to this token. */
    ACCEPTED,
    /**
     * FCM's {@code UNREGISTERED}: the app was uninstalled, or the token expired or was rotated. It
     * will never accept a message again -- the caller should revoke it via
     * {@link com.finora.notification.api.DeviceTokenService#revoke}. Routine.
     */
    TOKEN_DEAD,
    /**
     * FCM's {@code INVALID_ARGUMENT}: revoked the same way as {@link #TOKEN_DEAD}, because the message
     * shape is fixed and a corrupt token is the realistic cause. But FCM raises the same code for a
     * malformed message, and then every device a push reaches would be unregistered. So it is kept
     * apart from {@link #TOKEN_DEAD}, and the caller raises an alert for it (see
     * {@link com.finora.observability.PushTokenRevocations}). Expected to be near zero.
     */
    TOKEN_INVALID,
    /**
     * A retryable failure: an outage, a rate limit, or -- deliberately -- anything this sender is
     * not confident is permanent. Revoking a live device on an ambiguous signal is worse than
     * retrying a dead one a few extra times, so the default for anything uncertain is this, not
     * {@link #TOKEN_DEAD}.
     */
    TRANSIENT_FAILURE
}
