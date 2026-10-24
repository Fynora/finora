package com.finora.service;

import com.finora.config.RateLimiter;
import com.finora.util.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * A ceiling on how many password-reset and sign-in-code emails ONE ACCOUNT can be sent, whoever
 * asks for them.
 *
 * <p>Every other limit in this app is keyed by the caller's IP ({@link
 * com.finora.config.RateLimitFilter}), which bounds one client but not the mail an account's owner
 * receives. On 2026-10-24 a reset email and a sign-in-code email reached an owner who was asleep,
 * and nothing capped how many more could follow from other addresses: the IP limit gives each
 * address its own allowance, and the only per-account guard was a 30-second wait between codes.
 *
 * <p><b>The cost, stated plainly.</b> A ceiling on an account's requests can be used against that
 * account: someone who keeps asking can keep the owner's own request over the limit. The windows
 * are short (15 and 10 minutes by default) so the owner is never shut out for long. Neither limit
 * touches signing in with a password, Google or Apple, and the two limits are independent: an
 * owner held off from reset can still ask for a sign-in code, and the other way round, so
 * blocking both takes sustained requests of both kinds. Reset is the stricter of the two (3 per
 * 15 minutes against 5 per 10), because a reset email is the one an attacker most wants to
 * flood or to hold off.
 *
 * <p>Built on {@link RateLimiter}, so it counts in Redis and degrades to per-instance counting,
 * not to no counting, when Redis is unreachable.
 *
 * <p>The keys are hashes, never the address itself: Redis should not hold a list of who asked for
 * a password reset.
 */
@Component
public class AccountRequestThrottle {

    private final RateLimiter resetRequests;
    private final RateLimiter codeRequests;
    private final long resetWindowSeconds;
    private final long codeWindowSeconds;

    public AccountRequestThrottle(
            StringRedisTemplate redisTemplate,
            @Value("${app.rate-limit.reset-request-per-account.max:3}") int resetMax,
            @Value("${app.rate-limit.reset-request-per-account.window-seconds:900}") long resetWindowSeconds,
            @Value("${app.rate-limit.code-request-per-account.max:5}") int codeMax,
            @Value("${app.rate-limit.code-request-per-account.window-seconds:600}") long codeWindowSeconds) {
        this.resetRequests = new RateLimiter(resetMax, resetWindowSeconds, "reset-request-account", redisTemplate);
        this.codeRequests = new RateLimiter(codeMax, codeWindowSeconds, "code-request-account", redisTemplate);
        this.resetWindowSeconds = resetWindowSeconds;
        this.codeWindowSeconds = codeWindowSeconds;
    }

    /**
     * Counts this password-reset request and says whether the account is over its ceiling.
     *
     * <p>Keyed by the address AS TYPED, for known and unknown addresses alike, so the known and
     * unknown paths of {@code forgotPassword} do the same work here and a refusal reveals nothing
     * about whether the account exists: the caller refuses the same way, with the same message,
     * whichever kind of address it was.
     * A mock returns {@code false}, so tests that do not care about throttling are unaffected.
     */
    public boolean resetRequestsExceeded(String scope, String typedEmail) {
        String normalised = typedEmail == null ? "" : typedEmail.trim().toLowerCase(Locale.ROOT);
        return !resetRequests.allow(TokenHasher.sha256(scope + ":" + normalised));
    }

    /** Counts this sign-in-code request and says whether the account is over its ceiling. Only
     *  called for a request that would otherwise issue a code, so a refused or too-soon one does
     *  not use up the allowance. */
    public boolean codeRequestsExceeded(UUID userId) {
        return !codeRequests.allow(TokenHasher.sha256(userId.toString()));
    }

    /** The longest an owner can be told to wait for a reset email: the whole window. */
    public long resetWindowSeconds() {
        return resetWindowSeconds;
    }

    /** The longest the owner can be told to wait: the whole window, since the limiter does not
     *  report when the oldest request ages out. */
    public long codeWindowSeconds() {
        return codeWindowSeconds;
    }
}
