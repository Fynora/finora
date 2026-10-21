package com.finora.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Device tokens revoked because FCM rejected a push, counted by reason, and an alert for the one
 * reason that can mean a bug on our side.
 *
 * <p>Two FCM codes revoke a token. {@code UNREGISTERED} is FCM's own signal that the app was
 * uninstalled or the token expired: routine, counted only. {@code INVALID_ARGUMENT} is revoked on
 * the reasoning that the message shape is fixed, so a corrupt token is the realistic cause. But FCM
 * raises the same code for a malformed message, and then every device a push reaches is
 * unregistered, one after another, with nothing else to show for it: a push to a user with no
 * device left ends SKIPPED, by design, not as a dead letter. So each {@code INVALID_ARGUMENT}
 * revocation is a Sentry error. It is expected to be near zero; one corrupt token raises one issue,
 * and a malformed message raises the same issue on its first send.
 *
 * <p>Logout revocations ({@code DeviceTokenController}) are not counted here: only revocations
 * caused by an FCM rejection.
 */
@Component
public class PushTokenRevocations {

    static final String METRIC = "finora.push.device_token_revoked";
    static final String ALERT_MESSAGE = "FCM rejected a push as INVALID_ARGUMENT and its device token "
            + "was revoked. One means a corrupt token; several mean the push message itself is "
            + "malformed and every device it reaches is being unregistered.";

    private final MeterRegistry registry;

    public PushTokenRevocations(MeterRegistry registry) {
        this.registry = registry;
    }

    /** FCM's {@code UNREGISTERED}: the app was uninstalled or the token expired. Counted only. */
    public void unregistered() {
        count("unregistered");
    }

    /**
     * FCM's {@code INVALID_ARGUMENT}: counted, and reported to Sentry at ERROR.
     *
     * @param notificationId the notification whose send was rejected -- an internal row id, never
     *     customer data, sent as the allowlisted {@code jobId} tag (see {@link SentryScrubber}).
     */
    public void invalidArgument(UUID notificationId) {
        count("invalid_argument");
        Sentry.withScope(scope -> {
            scope.setTag("phase", "push-send");
            scope.setTag("outcome", "token-revoked-invalid-argument");
            if (notificationId != null) scope.setTag("jobId", notificationId.toString());
            scope.setLevel(SentryLevel.ERROR);
            Sentry.captureMessage(ALERT_MESSAGE);
        });
    }

    private void count(String reason) {
        Counter.builder(METRIC)
                .description("Device tokens revoked because FCM rejected a push, by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
