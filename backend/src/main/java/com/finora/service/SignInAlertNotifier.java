package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.entity.ClientPlatform;
import com.finora.support.ClientIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells the owner of an account that it was just signed in to.
 *
 * <p>Before this, a sign-in the owner did not make was visible in one place: the device list in
 * Settings, to someone who thought to look. A stolen password or one phished code gave a session
 * the owner had no reason to know about. This sends an email and a push on every sign-in, with
 * the device, browser, address and time, and what to do if it was not them.
 *
 * <h2>Every sign-in, not only an unfamiliar one</h2>
 *
 * <p>The owner's decision (2026-11-20). The server has no real device fingerprint, only labels
 * such as "Chrome" on "Windows", so "a device we have not seen before" could only have meant "a
 * browser and device type we have not seen before", and someone using the same kind of browser as
 * the owner would have looked familiar. Telling them every time has no such gap.
 *
 * <h2>What does not send one</h2>
 *
 * <ul>
 *   <li>Signing up. The person is creating the account; there is no earlier owner to tell.</li>
 *   <li>A token refresh. That continues a session; it does not begin one.</li>
 * </ul>
 *
 * <p>The session a suspended customer is given for the suspended screen DOES send one. It is a
 * refusal, but whoever holds it has proved the account's password and can read why the account is
 * suspended and download its data, which is what the owner needs to hear about.
 *
 * <h2>The email goes only to a verified address</h2>
 *
 * <p>Anyone can register an account with an address that is not theirs and leave it unverified.
 * Each of their sign-ins would then put this mail in a stranger's inbox. The account-locked mail
 * refuses an unverified address for the same reason. The push is unaffected: it goes to phones
 * already signed in to the account.
 *
 * <h2>It can never fail a sign-in</h2>
 *
 * <p>{@link NotificationService#request} does not throw and writes its rows in the caller's
 * transaction, so the alert is recorded together with the session it is about and sent after that
 * commits. Everything this class does before that call is caught too. A sign-in that works but
 * tells nobody is a smaller failure than a sign-in that is refused because an alert could not be
 * written.
 */
@Service
public class SignInAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(SignInAlertNotifier.class);

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    /** What the message says where the request carried nothing to work a label out from. */
    static final String UNKNOWN = "Unknown";

    private final NotificationService notificationService;
    private final RequestMetadata requestMetadata;
    private final ClientIdentity clientIdentity;
    private final boolean enabled;

    public SignInAlertNotifier(NotificationService notificationService, RequestMetadata requestMetadata,
                               ClientIdentity clientIdentity,
                               @Value("${app.security.sign-in-alert-enabled:true}") boolean enabled) {
        this.notificationService = notificationService;
        this.requestMetadata = requestMetadata;
        this.clientIdentity = clientIdentity;
        this.enabled = enabled;
    }

    /**
     * @param user      the account that was signed in to
     * @param sessionId the session that sign-in created; one alert per session
     */
    public void signedIn(User user, UUID sessionId) {
        if (!enabled) return;
        try {
            Set<NotificationChannel> channels = EnumSet.of(NotificationChannel.PUSH);
            if (user.isEmailVerified()) {
                channels.add(NotificationChannel.EMAIL);
            }
            notificationService.request(NotificationRequest.of(
                    user.getId(),
                    NotificationType.NEW_SIGN_IN,
                    NotificationCategory.SECURITY,
                    NotificationPriority.HIGH,
                    "NEW_SIGN_IN_" + sessionId,
                    channels,
                    Map.of("device", device(platform()),
                            "browser", appOrBrowser(platform()),
                            "ip", orUnknown(requestMetadata.ip()),
                            "time", formatted(Instant.now()))));
        } catch (RuntimeException e) {
            log.error("Could not queue the sign-in alert for user {}", user.getId(), e);
        }
    }

    /**
     * The phone app does not send a browser's user agent, so worked out from that alone a sign-in
     * from the app read "Device: Other. Browser: Other", which tells the owner nothing. The app says
     * which platform it is in its own header, and that is used for the label here.
     *
     * <p>The header is the client's own claim ({@link ClientIdentity}). That is acceptable for a
     * label in a message: it decides nothing, and someone who lies about it still causes the alert,
     * with the real address and time on it.
     */
    private ClientPlatform platform() {
        try {
            ClientPlatform platform = clientIdentity.platform();
            return platform == null ? ClientPlatform.WEB : platform;
        } catch (RuntimeException e) {
            return ClientPlatform.WEB;
        }
    }

    private String device(ClientPlatform platform) {
        return switch (platform) {
            case MOBILE_ANDROID -> "Android phone";
            case MOBILE_IOS -> "iPhone";
            case WEB -> orUnknown(requestMetadata.device());
        };
    }

    private String appOrBrowser(ClientPlatform platform) {
        return switch (platform) {
            case MOBILE_ANDROID, MOBILE_IOS -> "Fynora app";
            case WEB -> orUnknown(requestMetadata.browser());
        };
    }

    static String formatted(Instant when) {
        return WHEN.format(when.atZone(INDIA)) + " IST";
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
