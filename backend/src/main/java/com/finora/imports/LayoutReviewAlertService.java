package com.finora.imports;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.util.EmailMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.Map;

/**
 * Emails every admin holding {@code LAYOUT_REGISTRY_MANAGE} when a statement layout is newly
 * flagged for review -- see {@link LayoutReviewService}.
 *
 * <p>Carries the layout fingerprint, the reasons and the analysis reference, and nothing about the
 * upload itself: not the file name (real statement file names embed card and account numbers), not
 * the user, not a single transaction. The link opens the admin portal, where the analysis session
 * is behind the portal's own permissions. Same direct-to-{@link EmailProvider} pattern as
 * {@code HeldItemAdminAlertService}, for the same reason: this is "everyone holding permission X",
 * which the per-user notification preferences system has no shape for.
 */
@Service
public class LayoutReviewAlertService {

    private static final Logger log = LoggerFactory.getLogger(LayoutReviewAlertService.class);

    static final String LAYOUT_REGISTRY_MANAGE = "LAYOUT_REGISTRY_MANAGE";

    /** Plain-language explanation per reason code, so the email reads without the codebase open. */
    private static final Map<String, String> REASON_TEXT = Map.of(
            "NEW_LAYOUT", "A statement layout Finora has not seen before",
            "VERIFICATION_NOT_PASSED", "A verification check did not pass (warning or failure)",
            "BLANK_DESCRIPTIONS", "Most transactions staged with no description",
            "STAGING_FAILED", "The layout was recognised but staging failed",
            "IDENTITY_CONFLICT", "The layout was seen as a different bank or account type than before",
            "HOLDER_NAME_UNREADABLE", "The account holder's name could not be read (the statement still imported, with no holder)");

    private final UserRepository userRepository;
    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;

    public LayoutReviewAlertService(UserRepository userRepository, EmailProvider emailProvider,
                                    EmailProperties emailProperties) {
        this.userRepository = userRepository;
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
    }

    /** Runs on {@code layoutReviewAlertExecutor}, never on the upload request -- see that bean. */
    @org.springframework.scheduling.annotation.Async("layoutReviewAlertExecutor")
    public void alertLayoutNeedsReview(String fingerprint, List<String> reasons, String analysisReference,
                                       String profile) {
        String subject = "Statement layout needs review — " + fingerprint;
        StringBuilder reasonItems = new StringBuilder();
        for (String reason : reasons) {
            reasonItems.append("<li>").append(escape(describe(reason))).append("</li>");
        }
        String referenceLine = analysisReference == null || analysisReference.isBlank()
                ? "" : "<li><strong>Analysis:</strong> " + escape(analysisReference) + "</li>";
        String profileLine = profile == null || profile.isBlank()
                ? "<li><strong>Grouped as:</strong> not grouped (bank or account type not detected)</li>"
                : "<li><strong>Grouped as:</strong> " + escape(profile) + "</li>";
        String html = "<p>A statement layout was flagged for review while a statement was being staged.</p>"
                + "<ul>"
                + "<li><strong>Layout:</strong> " + escape(fingerprint) + "</li>"
                + profileLine
                + referenceLine
                + "</ul>"
                + "<p><strong>Why:</strong></p><ul>" + reasonItems + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/layout-intelligence?tab=review\">Open the layout review queue</a></p>";

        List<User> recipients = userRepository.findByPermissionNameAndAccountScope(LAYOUT_REGISTRY_MANAGE, User.SCOPE_ADMIN);
        if (recipients.isEmpty()) {
            log.warn("No admin holds {} -- no layout review alert sent for {}", LAYOUT_REGISTRY_MANAGE, fingerprint);
            return;
        }
        for (User recipient : recipients) {
            try {
                EmailResult result = emailProvider.send(EmailMessage.html(recipient.getEmail(), subject, html));
                if (!result.success()) {
                    log.warn("Layout review alert to {} failed: {}",
                            EmailMasking.mask(recipient.getEmail()), result.failureReason());
                }
            } catch (RuntimeException e) {
                log.warn("Layout review alert to {} threw", EmailMasking.mask(recipient.getEmail()), e);
            }
        }
    }

    /** "VERIFICATION_NOT_PASSED:BALANCE_CHAIN" reads as the base text plus the rule's name. */
    static String describe(String reason) {
        int colon = reason.indexOf(':');
        if (colon < 0) return REASON_TEXT.getOrDefault(reason, reason);
        String base = reason.substring(0, colon);
        return REASON_TEXT.getOrDefault(base, base) + " — " + reason.substring(colon + 1);
    }

        /** Same reasoning as HeldItemAdminAlertService.adminBaseUrl: never fall back to the user app. */
    private String adminBaseUrl() {
        String base = emailProperties.getAdminAppBaseUrl();
        if (base == null || base.isBlank()) {
            log.warn("ADMIN_APP_BASE_URL is not configured -- layout review alert emails will link with "
                    + "no domain until it is set");
            return "";
        }
        return base;
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
