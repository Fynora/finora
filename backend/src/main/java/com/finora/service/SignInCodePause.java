package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.EmailLoginOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pauses sign-in by emailed code for an account that has had too many wrong codes entered.
 *
 * <p>Audit F-07 (2026-11-20). A code is six digits. Each one allowed five tries, and an account
 * could be sent five codes every ten minutes, but nothing counted the total. Someone using a few
 * addresses could make about 3,600 guesses a day against one account, indefinitely: roughly a 10%
 * chance of getting in over a month. The owner's only sign was the stream of code emails.
 *
 * <p>Now, once {@code max} wrong codes (10 by default) have been entered for an account within 24
 * hours, no code is checked and no new code is sent for it until enough of them are a day old.
 * That is at most ten guesses a day. The owner is told when the pause begins.
 *
 * <h2>Where the count comes from</h2>
 *
 * <p>From the code rows themselves: each {@code email_login_otps} row already counts the wrong
 * tries made against it, and the retention sweep keeps a row for 24 hours, which is why the
 * window is 24 hours and not configurable. No new column, and nothing to reset. A try that is
 * refused because of the pause is not counted, so being paused does not extend the pause.
 *
 * <h2>What it costs</h2>
 *
 * <p>The owner's decision (2026-11-21), over a plain daily cap on codes. Someone who is not the
 * owner can pause code sign-in for an account by entering ten wrong codes. The owner's password,
 * Google and Apple sign-in are untouched, and wrong codes never count toward the password
 * lockout, so the two cannot be combined into one cheap lock-out. An owner who mistypes ten codes
 * in a day pauses themselves.
 *
 * <h2>What it does not cover</h2>
 *
 * <p>Codes sent to a phone. Those are Firebase's, which applies its own limits; this server never
 * sees a wrong one.
 */
@Service
public class SignInCodePause {

    private static final Logger log = LoggerFactory.getLogger(SignInCodePause.class);

    /** Not configurable: it is how long EmailLoginOtpRetentionSweepService keeps the rows the
     *  count is read from, and that sweep takes its cutoff from this constant. */
    static final Duration WINDOW = Duration.ofHours(24);

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final EmailLoginOtpRepository emailLoginOtpRepository;
    private final NotificationService notificationService;
    private final RequestMetadata requestMetadata;
    private final int max;

    public SignInCodePause(EmailLoginOtpRepository emailLoginOtpRepository, NotificationService notificationService,
                           RequestMetadata requestMetadata,
                           @Value("${app.security.sign-in-code-max-wrong-per-day:10}") int max) {
        this.emailLoginOtpRepository = emailLoginOtpRepository;
        this.notificationService = notificationService;
        this.requestMetadata = requestMetadata;
        this.max = max;
    }

    /** How many wrong codes have been entered for this account in the last 24 hours. */
    public long wrongCodes(UUID userId) {
        return emailLoginOtpRepository.sumAttemptCountByUserIdSince(userId, Instant.now().minus(WINDOW));
    }

    /** Whether that many wrong codes pauses code sign-in. Never, when the limit is 0 or less. */
    public boolean pausedAt(long wrongCodes) {
        return max > 0 && wrongCodes >= max;
    }

    public boolean isPaused(UUID userId) {
        return max > 0 && pausedAt(wrongCodes(userId));
    }

    /**
     * What a paused account's code request or code sign-in is answered with. Said plainly, with
     * the way forward: whether the account exists was already disclosed by /auth/identify before
     * either endpoint is reached, and both apps show this message as it is.
     */
    public ApiException refusal() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.AUTH_OTP_RESEND_COOLDOWN,
                "Sign-in by emailed code is paused for this account because too many wrong codes were entered. "
                        + "Sign in with your password, or try a code again tomorrow.",
                Map.of("reason", "SIGN_IN_CODE_PAUSED"));
    }

    /**
     * Called after a wrong code has been counted. Tells the owner if that one began the pause.
     *
     * <p>At most one alert a day for an account. As the oldest wrong codes pass 24 hours the
     * count dips under the limit and a single further wrong code crosses it again; keyed on the
     * day, those crossings do not each send mail.
     *
     * <p>Never throws: the wrong code is already refused, and the alert must not turn that
     * refusal into a server error.
     *
     * @param wrongBefore the count before this wrong code
     */
    public void wrongCodeCounted(User user, long wrongBefore) {
        if (pausedAt(wrongBefore) || !pausedAt(wrongBefore + 1)) return;
        try {
            log.warn("Sign-in by emailed code paused for user {} after {} wrong codes in 24 hours",
                    user.getId(), wrongBefore + 1);
            // Email only to a verified address, the rule SignInAlertNotifier gives the reason
            // for. A code can only be requested for a verified address, so this matters only if
            // the address stopped being verified after the codes were sent.
            Set<NotificationChannel> channels = EnumSet.of(NotificationChannel.PUSH);
            if (user.isEmailVerified()) {
                channels.add(NotificationChannel.EMAIL);
            }
            notificationService.request(NotificationRequest.of(
                    user.getId(),
                    NotificationType.SIGN_IN_CODE_PAUSED,
                    NotificationCategory.SECURITY,
                    NotificationPriority.HIGH,
                    "SIGN_IN_CODE_PAUSED_" + user.getId() + "_" + LocalDate.now(INDIA),
                    channels,
                    Map.of("count", String.valueOf(wrongBefore + 1),
                            "ip", orUnknown(requestMetadata.ip()),
                            "time", SignInAlertNotifier.formatted(Instant.now()))));
        } catch (RuntimeException e) {
            log.error("Could not queue the sign-in code pause alert for user {}", user.getId(), e);
        }
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }
}
