package com.finora.integrations.google.merchant;

import java.time.LocalDate;

/**
 * A message body, already through {@link MerchantEmailSanitizer} — Phase C5.
 *
 * <p>This is the ONLY type {@link MerchantEmailParser} accepts. There is deliberately no
 * constructor or factory here that takes a raw string: the type itself is the enforcement that a
 * parser cannot be handed unsanitized HTML by a future caller who forgets the step, because there
 * is no code path that produces one without going through the sanitizer first.
 *
 * @param gmailMessageId       Gmail's id for the message — the provenance key, and the only
 *                             identifier that survives into a staged row (C5-B).
 * @param authenticatedDomain  the domain the C3 gate verified this message against. Routing
 *                             ({@code MerchantEmailParser.canParse}) uses this, never anything
 *                             parsed from the body — the body is attacker-shaped even after
 *                             sanitization; the domain is not, because it already passed C3.
 * @param safeHtml             sanitized HTML: block and table structure preserved, no script, no
 *                             event handler, no link, no image. Suitable for a parser that needs
 *                             layout (e.g. reading a specific table cell).
 * @param plainText            {@code safeHtml} with tags stripped — the common case, for parsers
 *                             that match against wording rather than structure.
 * @param receivedOn           the day Gmail received the message, or {@code null} when it is not
 *                             known. For a receipt whose body carries no date of its own (Amazon's
 *                             order confirmation says only "Arriving tomorrow"), the day the
 *                             confirmation arrived is the day the order was placed. It is also the
 *                             reference that lets a year-less date ("Mon, 13 Jul") be resolved. It
 *                             is Gmail's own message timestamp, not a header the sender wrote.
 */
public record SanitizedGmailMessage(String gmailMessageId, String authenticatedDomain,
                                    String safeHtml, String plainText, LocalDate receivedOn) {

    /** A message whose arrival day is not known. */
    public SanitizedGmailMessage(String gmailMessageId, String authenticatedDomain,
                                 String safeHtml, String plainText) {
        this(gmailMessageId, authenticatedDomain, safeHtml, plainText, null);
    }
}
