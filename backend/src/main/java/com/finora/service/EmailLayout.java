package com.finora.service;

import java.time.Year;
import org.springframework.web.util.HtmlUtils;

/**
 * The one branded HTML shell for every transactional email Fynora sends. Originally scoped to
 * just the two DB-template notification emails (IMPORT_STATEMENT_HELD, IMPORT_STATEMENT_READY,
 * "make the email beautiful", 2026-09-06); generalized here (premium redesign, 2026-09-11) to
 * also back every hardcoded email in {@link ResendEmailProvider}, so a customer recognizes a
 * Fynora email by its shell regardless of which send path produced it.
 *
 * <p>Table-based layout with every style attribute inlined, not a {@code <style>} block or
 * external stylesheet -- the only layout approach that renders consistently across Outlook's Word
 * rendering engine, Gmail's stripped {@code <head>}, and everything in between. No web fonts
 * either (unreliable in mail clients); the font stack falls through to whatever system UI font
 * each client already has.
 *
 * <p>Colors are the product's actual brand palette (graphite/cream), not invented ones -- see
 * {@code frontend/src/index.css}'s {@code --color-primary}/{@code --color-primary-light} custom
 * properties, the source of truth this hardcodes hex equivalents of (a mail client cannot read a
 * CSS custom property, so there is no way to reference that file directly).
 */
public final class EmailLayout {

    private EmailLayout() {}

    private static final String GRAPHITE = "#262A33";
    private static final String CREAM = "#F4F1EC";
    private static final String PAGE_BACKGROUND = "#F8FAFC";
    private static final String BORDER = "#E5E7EB";
    private static final String MUTED_TEXT = "#6B7280";
    private static final String FONT_STACK =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    /** Verified against Terms.tsx/Privacy.tsx/Contact.tsx and the billing invoice config
     *  (BILLING_INVOICE_ENTITY_NAME) before use -- not invented. */
    private static final String LEGAL_ENTITY = "Fynora Technovation LLP";

    /**
     * A call-to-action button, rendered as a table-based, Outlook-safe button rather than a bare
     * {@code <a>} (several mail clients render a bare link as plain underlined text with no
     * visual affordance). {@code url} is never escaped -- every caller today builds it from a
     * backend-generated token link or a hardcoded in-app route, never from raw user input.
     */
    public record CtaButton(String label, String url) {}

    /**
     * Which closing line appears above the always-present legal line.
     * <ul>
     *   <li>{@code SUPPORT_REPLY}: this message went out from {@code support@} and a reply
     *       reaches a person.</li>
     *   <li>{@code SUPPORT_LINK}: this message went out from {@code noreply@}; a mailto link is
     *       the real way to reach a person instead.</li>
     *   <li>{@code NONE}: the body copy already covers how to get help (e.g. a billing email that
     *       says "just reply to this email" inline), so no separate line is added.</li>
     * </ul>
     */
    public enum Footer { SUPPORT_REPLY, SUPPORT_LINK, NONE }

    /**
     * Legacy entry point -- unchanged behavior from the original single-purpose shell, still used
     * by {@code EmailNotificationProvider} for any future {@code notification_templates}-driven
     * email. {@code bodyText} is operator-authored plain text, HTML-escaped before insertion since
     * a stray angle bracket in a database row must never corrupt the layout around it. See
     * {@link #wrap(String, String, CtaButton, Footer, String)} for the trusted-HTML entry point
     * every hardcoded Java email uses instead.
     */
    public static String wrap(String heading, String bodyText, boolean supportSender, String supportAddress) {
        String safeBody = HtmlUtils.htmlEscape(bodyText).replace("\n", "<br>");
        Footer footer = supportSender ? Footer.SUPPORT_REPLY : Footer.SUPPORT_LINK;
        String paragraph = "<p style=\"margin:0;font-size:15px;line-height:1.6;color:" + GRAPHITE
                + ";\">" + safeBody + "</p>";
        return wrap(heading, paragraph, null, footer, supportAddress);
    }

    /**
     * Wraps trusted, caller-built HTML (a heading plus body paragraphs, and an optional CTA
     * button) in the branded shell every transactional email shares.
     *
     * @param heading rendered large -- a customer's inbox already shows the subject line once, so
     *                this restates the point of the email in plain language, not a page title.
     * @param bodyHtml TRUSTED HTML the caller already built (a Java text block of {@code <p>}
     *                 paragraphs) -- unlike the legacy overload above, this is never escaped.
     *                 Every caller today is a hardcoded Java string literal, not operator- or
     *                 user-authored markup; a caller that interpolates a dynamic value into it is
     *                 responsible for escaping that value itself if it ever needs to.
     * @param cta optional call-to-action button; {@code null} renders no button.
     * @param footer which closing line appears above the always-present legal line.
     * @param supportAddress the live, configured support address ({@code EmailProperties
     *                       .getSupportFromAddress()}) -- passed in, not hardcoded, so a
     *                       deployment that overrides {@code EMAIL_FROM_SUPPORT} doesn't leave
     *                       this shell telling customers the old address.
     */
    public static String wrap(String heading, String bodyHtml, CtaButton cta, Footer footer,
            String supportAddress) {
        String safeHeading = HtmlUtils.htmlEscape(heading);
        String safeSupportAddress = HtmlUtils.htmlEscape(supportAddress);
        String footerLine = switch (footer) {
            case SUPPORT_REPLY -> "Questions? Just reply to this email and it'll reach us.";
            case SUPPORT_LINK -> "Need help? Email <a href=\"mailto:" + safeSupportAddress
                    + "\" style=\"color:" + GRAPHITE + ";\">" + safeSupportAddress + "</a>.";
            case NONE -> "";
        };
        String buttonHtml = cta == null ? "" : """
                <table role="presentation" cellpadding="0" cellspacing="0" style="margin:24px 0 0 0;">
                  <tr>
                    <td style="border-radius:8px;background:%s;">
                      <a href="%s" style="display:inline-block;padding:12px 24px;font-size:14px;\
                font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">%s</a>
                    </td>
                  </tr>
                </table>
                """.formatted(GRAPHITE, cta.url(), HtmlUtils.htmlEscape(cta.label()));
        String legalLine = "&copy; " + Year.now().getValue() + " " + LEGAL_ENTITY + ". All rights reserved.";
        String footerBlock = footerLine.isEmpty()
                ? "<p style=\"margin:0;font-size:12px;color:" + MUTED_TEXT + ";\">" + legalLine + "</p>"
                : "<p style=\"margin:0 0 6px 0;font-size:13px;color:" + MUTED_TEXT + ";\">" + footerLine
                        + "</p><p style=\"margin:0;font-size:12px;color:" + MUTED_TEXT + ";\">" + legalLine
                        + "</p>";

        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" \
                style="background:%s;padding:32px 16px;font-family:%s;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="480" cellpadding="0" cellspacing="0" \
                style="max-width:480px;width:100%%;background:#ffffff;border-radius:12px;\
                border:1px solid %s;overflow:hidden;">
                        <tr>
                          <td style="height:4px;background:%s;font-size:0;line-height:0;">&nbsp;</td>
                        </tr>
                        <tr>
                          <td style="padding:20px 32px;background:%s;">
                            <p style="margin:0;font-size:13px;font-weight:700;letter-spacing:0.08em;\
                color:%s;text-transform:uppercase;">FYNORA</p>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:24px 32px 0 32px;">
                            <h1 style="margin:0 0 16px 0;font-size:20px;line-height:1.3;color:%s;\
                font-weight:600;">%s</h1>
                            %s
                            %s
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:28px 32px 32px 32px;">
                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                              <tr><td style="border-top:1px solid %s;padding-top:16px;">
                                %s
                              </td></tr>
                            </table>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                """.formatted(
                PAGE_BACKGROUND, FONT_STACK, BORDER,
                GRAPHITE,
                CREAM, GRAPHITE,
                GRAPHITE, safeHeading, bodyHtml, buttonHtml,
                BORDER, footerBlock);
    }
}
