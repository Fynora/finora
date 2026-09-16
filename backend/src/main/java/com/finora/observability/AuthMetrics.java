package com.finora.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The session-policy question raised alongside the idle/absolute timeout change (24h idle / 30
 * days absolute, replacing 30 min / 7 days -- see the commit that added this class): without these
 * four counters, "how many users are actually hitting the new limits" was a guess, not a
 * measurement. Same reasoning as {@link ReconciliationMetrics}'s own doc comment -- production
 * data over continued speculation -- applied to session policy instead of reconciliation.
 *
 * <p>Deliberately not built on the worker contract in {@code docs/engineering/observability.md}
 * §7. Login and refresh are synchronous, request-thread operations with no queue, no retry and no
 * dead-letter concept -- the same shape {@link ReconciliationMetrics} is in, for the same reason.
 *
 * <h2>No tags</h2>
 *
 * <p>Unlike {@link ReconciliationMetrics}, none of these four events has a safe, bounded dimension
 * worth splitting by -- the login method (password/Google/Apple/reactivation) and the client
 * platform are exactly the kind of thing worth knowing, but {@code X-Client-Platform} is
 * client-asserted (see {@code ClientIdentity}'s own doc comment) and not a value this class will
 * carry as a tag on a security-relevant counter. Four plain counters answer the question that
 * motivated this class -- volume of each outcome -- without that risk.
 */
@Component
public class AuthMetrics {

    private final MeterRegistry registry;

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A new session minted -- {@link com.finora.service.RefreshTokenService#issue} called from a
     *  fresh login, MFA completion, Google/Apple sign-in, or reactivation. Not incremented by
     *  rotation, which is a refresh of an existing session, not a new one. */
    public void loginSucceeded() {
        Counter.builder("finora.auth.login_success")
                .description("A new session minted from any successful sign-in path")
                .register(registry)
                .increment();
    }

    /** An existing session's refresh token rotated successfully -- inside both the idle and
     *  absolute windows. */
    public void refreshSucceeded() {
        Counter.builder("finora.auth.refresh_success")
                .description("A refresh token rotated successfully")
                .register(registry)
                .increment();
    }

    /** A refresh was refused because the token sat unused longer than {@code idle-timeout-ms} --
     *  {@code ErrorCode.AUTH_SESSION_IDLE}. */
    public void refreshExpiredIdle() {
        Counter.builder("finora.auth.refresh_expired_idle")
                .description("A refresh was refused for exceeding the idle timeout")
                .register(registry)
                .increment();
    }

    /** A refresh was refused because the session's total age (from {@code sessionStartedAt},
     *  unaffected by rotation) exceeded {@code absolute-session-ms} --
     *  {@code ErrorCode.AUTH_SESSION_MAX_AGE}. */
    public void refreshExpiredAbsolute() {
        Counter.builder("finora.auth.refresh_expired_absolute")
                .description("A refresh was refused for exceeding the absolute session cap")
                .register(registry)
                .increment();
    }
}
