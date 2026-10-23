package com.finora.support;

import com.finora.config.EmailProperties;
import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.SupportTicket;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.util.EmailMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Emails every active admin and super admin when a customer raises a support ticket or sends
 * feedback, so the queue does not have to be opened to find out.
 *
 * <p><b>Who gets it.</b> Whoever holds {@code SUPPORT_MANAGE} in the admin scope and is active --
 * the permission that already decides who can open the ticket queue and the feedback page, which
 * V149 grants to both ADMIN and SUPER_ADMIN. Resolved from the role graph on every send, so an
 * admin added or removed tomorrow is picked up with no configuration. Suspended or deactivated
 * accounts are skipped (the bootstrap admin is suspended once setup completes).
 *
 * <p><b>What it says.</b> A pointer, not a copy. The ticket number or the feedback type, category
 * or context, where it came from, when, and a link into the admin portal. Never the subject, the
 * description or the feedback message, and never the sender's name or email: those are a customer's
 * free text about their own money and their identity, and email is a weaker channel than the
 * authenticated portal (forwarding, a compromised inbox). {@code HeldItemAdminAlertService} makes
 * the same call for statement content. The portal shows who sent it and the full text.
 *
 * <p><b>Off the request.</b> {@code @Async} on its own executor ({@code supportAlertExecutor}): the
 * email provider can take seconds per recipient, and a customer submitting a ticket must not wait
 * for that. The caller passes the values it already holds instead of an id to re-read, so this
 * touches the database only to look up recipients, and always on its own thread.
 *
 * <p><b>Never throws, never blocks the ticket.</b> A failed send is logged with the address
 * masked and the next recipient still gets theirs. No recipients (permission held by nobody, or the
 * email provider not configured) is logged and is not an error: the ticket or feedback is already
 * saved and visible in the portal either way.
 */
@Service
public class SupportAdminAlertService {

    private static final Logger log = LoggerFactory.getLogger(SupportAdminAlertService.class);

    static final String SUPPORT_MANAGE = "SUPPORT_MANAGE";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final UserRepository userRepository;
    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;

    public SupportAdminAlertService(UserRepository userRepository, EmailProvider emailProvider,
                                    EmailProperties emailProperties) {
        this.userRepository = userRepository;
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
    }

    /** A ticket was just saved. {@code ticketId} is what the portal's detail route is keyed on. */
    @Async("supportAlertExecutor")
    public void alertTicketRaised(UUID ticketId, String ticketNumber, SupportTicket.Category category,
                                  ClientPlatform source, Instant raisedAt) {
        String html = "<p>A customer raised a support ticket.</p>"
                + "<ul>"
                + "<li><strong>Ticket:</strong> " + escape(ticketNumber) + "</li>"
                + "<li><strong>Category:</strong> " + escape(label(category)) + "</li>"
                + "<li><strong>From:</strong> " + escape(label(source)) + "</li>"
                + "<li><strong>Raised at:</strong> " + escape(formatTimestamp(raisedAt)) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/support-tickets/" + ticketId + "\">Open this ticket</a></p>";
        sendToSupportAdmins("New support ticket — " + ticketNumber, html);
    }

    /** A feedback entry was just saved. The portal's feedback page is a list, so the link is to it. */
    @Async("supportAlertExecutor")
    public void alertFeedbackReceived(FeedbackEntry.Type type, FeedbackEntry.Context context,
                                      ClientPlatform source, Instant sentAt) {
        String html = "<p>A customer sent feedback.</p>"
                + "<ul>"
                + "<li><strong>Type:</strong> " + escape(label(type)) + "</li>"
                + "<li><strong>About:</strong> " + escape(label(context)) + "</li>"
                + "<li><strong>From:</strong> " + escape(label(source)) + "</li>"
                + "<li><strong>Sent at:</strong> " + escape(formatTimestamp(sentAt)) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/feedback\">Open the feedback page</a></p>";
        sendToSupportAdmins("New feedback — " + label(type), html);
    }

    private void sendToSupportAdmins(String subject, String html) {
        try {
            List<User> recipients = userRepository
                    .findByPermissionNameAndAccountScope(SUPPORT_MANAGE, User.SCOPE_ADMIN).stream()
                    .filter(u -> User.STATUS_ACTIVE.equals(u.getStatus()))
                    .toList();
            if (recipients.isEmpty()) {
                log.warn("No active admin holds {} -- no support alert sent for \"{}\"", SUPPORT_MANAGE, subject);
                return;
            }
            for (User recipient : recipients) {
                try {
                    EmailResult result = emailProvider.send(EmailMessage.html(recipient.getEmail(), subject, html));
                    if (!result.success()) {
                        log.warn("Support alert to {} failed: {}",
                                EmailMasking.mask(recipient.getEmail()), result.failureReason());
                    }
                } catch (RuntimeException e) {
                    log.warn("Support alert to {} threw", EmailMasking.mask(recipient.getEmail()), e);
                }
            }
        } catch (RuntimeException e) {
            // The recipient lookup itself failing must not surface anywhere: the ticket is saved.
            log.warn("Support alert \"{}\" could not be sent", subject, e);
        }
    }

    /** "TECHNICAL_ISSUE" -> "Technical issue". */
    private static String label(Enum<?> value) {
        if (value == null) return "Unknown";
        String words = value.name().replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String formatTimestamp(Instant instant) {
        return instant == null ? "unknown" : TIMESTAMP_FORMAT.format(instant);
    }

    /** Admin-portal links only: never falls back to the user app, same as the other admin alerts. */
    private String adminBaseUrl() {
        String base = emailProperties.getAdminAppBaseUrl();
        if (base == null || base.isBlank()) {
            log.warn("ADMIN_APP_BASE_URL is not configured -- support alert emails will link with no domain "
                    + "until it is set");
            return "";
        }
        return base;
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
