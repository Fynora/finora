# Premium Email Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign all 12 of Fynora's transactional emails to a premium, Mercury/Ramp/Stripe-style shell — one shared branded wrapper, styled CTA buttons, and a legal footer — replacing the plain unstyled `<p>` tags 10 of them use today, and moving the remaining 2 (statement-ready, statement-held) off the generic DB-template notification system so they can share the same wrapper.

**Architecture:** Generalize the existing `EmailLayout` HTML shell (already branded graphite/cream, already live on 2 emails) into a shared, public wrapper with an optional CTA button and a legal footer, then route all 12 emails — the 10 already hardcoded in `ResendEmailProvider` plus 2 new ones added there — through it. The 2 statement emails move off `notification_templates` (deactivated, not deleted) onto hardcoded `ResendEmailProvider` methods called directly by a new `StatementStatusNotifier` service, which also keeps the PUSH side on the existing notification outbox and preserves the held-email's existing no-double-send guarantee via a new `ImportJob` column.

**Tech Stack:** Java 21 (Spring Boot backend), JUnit 5 + AssertJ + Mockito, Flyway migrations, Testcontainers Postgres for migration ITs.

**Spec:** This plan's own Global Constraints below — synthesized from the conversation's design decisions (no separate spec doc). The approved copy for all 12 emails, and the legal-entity/address facts, were verified against the live repo before this plan was written (see each task's own citations).

## Global Constraints

- Every email keeps its current subject line and every stated fact (expiry durations, "cannot be undone," "no action needed") verbatim — only wording, structure, and the addition of CTA buttons change. The two subjects that intentionally change wording (`Verify your email address`, `Confirm your new email address` — both drop the word "Fynora") are the two exceptions, per the approved copy.
- New CTA buttons only where the approved copy specifies one: password reset, email verification, email-change verification, welcome, password-changed, invoice, statement-ready. No button on: account deactivated/reactivated/deleted, subscription-activated, statement-held.
- Legal footer entity/address: `Fynora Technovation LLP` — verified against `frontend/src/pages/Terms.tsx:20`, `Privacy.tsx:12`, `Contact.tsx:35`, and `backend/src/main/resources/application.yml:671` (`BILLING_INVOICE_ENTITY_NAME`). Do not invent a different name or address.
- Brand colors are the existing `EmailLayout` palette (`GRAPHITE #262A33`, `CREAM #F4F1EC`) — sourced from `frontend/src/index.css`'s real custom properties, not new colors.
- Table-based, fully-inlined-style HTML only (no `<style>` blocks, no web fonts) — required for Outlook/Gmail rendering consistency; this is `EmailLayout`'s existing, unchanged constraint.
- No escaping regression and no escaping fix: dynamic values interpolated into the 10 hardcoded `ResendEmailProvider` emails (`fullName`, `bankName`, `device`) are unescaped today and stay unescaped after this plan — fixing that is out of scope (flag separately if wanted).
- The 2 statement emails move off `notification_templates`/`EmailNotificationProvider` for the **EMAIL** channel only. **PUSH** stays exactly as it is today (same outbox, same `notification_templates` PUSH rows, untouched).
- Moving the statement emails off the outbox means losing its automatic retry/backoff and its `notification_key`-based no-double-send guarantee for the email leg. The held-statement email has an existing, explicitly-tested guarantee ("a reprocess that fails and re-holds the same job does not send a second one" — `ImportJobWorkerTest.aJobHeldAgainAfterAFailedReprocessReusesTheSameNotificationKey`) that must be preserved; this plan replaces it with a new `ImportJob` column (Task 5) rather than dropping it. The ready-statement email needs no equivalent column — both its call sites are already naturally single-fire (a `COMPLETED` status transition and an `approve()` call gated by `refuseIfResolved`), verified by reading both call sites, not assumed.
- Every existing test that currently asserts behavior this plan changes must be updated in the same task, not left red.

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/finora/service/EmailLayout.java` (new location; moved from `notification/provider`) | The one branded HTML shell. Legacy escaped-plain-text overload (unchanged behavior, used by `EmailNotificationProvider`) + new trusted-HTML overload with `CtaButton`/`Footer` for all 12 hardcoded emails. |
| `backend/src/main/java/com/finora/notification/provider/EmailNotificationProvider.java` | One-line import change only — its only caller relationship to `EmailLayout` is unaffected in behavior. |
| `backend/src/main/java/com/finora/service/EmailProvider.java` | Interface gains `sendStatementReadyEmail`/`sendStatementHeldEmail`. |
| `backend/src/main/java/com/finora/service/NoOpEmailProvider.java` | Implements the 2 new interface methods (dev-environment log-only fallback). |
| `backend/src/main/java/com/finora/service/ResendEmailProvider.java` | All 12 emails' real content — rewritten to use the new `EmailLayout` overload; each email's HTML-building logic extracted into a package-private `build*Message` method so it's unit-testable without mocking the HTTP transport. |
| `backend/src/main/java/com/finora/entity/ImportJob.java` | New nullable `statementHeldEmailSentAt` column/field + `markStatementHeldEmailSent(Instant)` check-and-set method, mirroring the existing `wasHeldForReview` pattern. |
| `backend/src/main/resources/db/migration/V190__import_job_statement_held_email_sent.sql` | Adds the new column. |
| `backend/src/main/resources/db/migration/V191__deactivate_statement_email_templates.sql` | Deactivates the `IMPORT_STATEMENT_READY`/`IMPORT_STATEMENT_HELD` **EMAIL** rows in `notification_templates` (PUSH rows untouched). |
| `backend/src/main/java/com/finora/service/StatementStatusNotifier.java` (new) | Single call site for "tell the user their statement status changed" — requests the PUSH notification via the existing outbox, then sends the branded email directly via `EmailProvider`, guarded by the new `ImportJob` column for the held case. Replaces the duplicated logic that today lives inline at 4 call sites across 2 files. |
| `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java` | Swaps its `NotificationService` dependency for `StatementStatusNotifier`; its 2 call sites become one-line delegations. |
| `backend/src/main/java/com/finora/service/HeldStatementService.java` | Same swap, same 2-call-site simplification. |

---

## Task 1: Generalize `EmailLayout` into a shared, public wrapper with CTA button + legal footer support

**Files:**
- Create: `backend/src/main/java/com/finora/service/EmailLayout.java`
- Delete: `backend/src/main/java/com/finora/notification/provider/EmailLayout.java`
- Modify: `backend/src/main/java/com/finora/notification/provider/EmailNotificationProvider.java:1-17` (add import, no other change)
- Create: `backend/src/test/java/com/finora/service/EmailLayoutTest.java` (moved + extended from `backend/src/test/java/com/finora/notification/provider/EmailLayoutTest.java`, which is deleted)

**Interfaces:**
- Produces: `EmailLayout.wrap(String heading, String bodyText, boolean supportSender, String supportAddress)` — legacy overload, unchanged behavior, now `public`.
- Produces: `EmailLayout.wrap(String heading, String bodyHtml, EmailLayout.CtaButton cta, EmailLayout.Footer footer, String supportAddress)` — new overload every `ResendEmailProvider` method (Task 4) and `StatementStatusNotifier`'s email methods (Task 4, `sendStatementReadyEmail`/`sendStatementHeldEmail`) call.
- Produces: `EmailLayout.CtaButton` record `(String label, String url)`.
- Produces: `EmailLayout.Footer` enum `{ SUPPORT_REPLY, SUPPORT_LINK, NONE }`.

- [ ] **Step 1: Write the failing tests for the new API**

Create `backend/src/test/java/com/finora/service/EmailLayoutTest.java` with the 6 existing tests (moved verbatim from `backend/src/test/java/com/finora/notification/provider/EmailLayoutTest.java`, same assertions, just calling the now-public legacy overload) plus these new ones:

```java
package com.finora.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailLayoutTest {

    private static final String SUPPORT_ADDRESS = "help@example.test";

    @Test
    void wrap_includesTheHeadingAndBody() {
        String html = EmailLayout.wrap("Statement under review",
                "We will notify you once it is ready.", true, SUPPORT_ADDRESS);

        assertThat(html).contains("Statement under review");
        assertThat(html).contains("We will notify you once it is ready.");
    }

    @Test
    void wrap_carriesTheBrandWordmark() {
        String html = EmailLayout.wrap("Title", "Body", true, SUPPORT_ADDRESS);

        assertThat(html).contains("FYNORA");
    }

    @Test
    void wrap_invitesAReplyWhenSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", true, SUPPORT_ADDRESS);

        assertThat(html).contains("reply to this email");
        assertThat(html).doesNotContain(SUPPORT_ADDRESS);
    }

    @Test
    void wrap_pointsToTheGivenSupportAddressWhenNotSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", false, SUPPORT_ADDRESS);

        assertThat(html).contains(SUPPORT_ADDRESS);
        assertThat(html).contains("mailto:" + SUPPORT_ADDRESS);
        assertThat(html).doesNotContain("reply to this email");
    }

    @Test
    void wrap_escapesHtmlInTheHeadingAndBody() {
        String html = EmailLayout.wrap("<script>alert(1)</script>",
                "5 > 3 & <b>bold</b> isn't real markup here", true, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&amp;");
        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void wrap_turnsNewlinesIntoLineBreaksInTheBody() {
        String html = EmailLayout.wrap("Title", "Line one\nLine two", true, SUPPORT_ADDRESS);

        assertThat(html).contains("Line one<br>Line two");
    }

    // -- new, rich-HTML overload --

    @Test
    void richWrap_rendersACtaButtonWhenGiven() {
        String html = EmailLayout.wrap("Reset your password", "<p>Click below.</p>",
                new EmailLayout.CtaButton("Reset Password", "https://app.fynora.net/reset?token=abc"),
                EmailLayout.Footer.SUPPORT_LINK, SUPPORT_ADDRESS);

        assertThat(html).contains("https://app.fynora.net/reset?token=abc");
        assertThat(html).contains("Reset Password");
    }

    @Test
    void richWrap_omitsTheButtonTableWhenCtaIsNull() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.SUPPORT_LINK, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("border-radius:8px;background:#262A33");
    }

    @Test
    void richWrap_doesNotEscapeTheTrustedBodyHtml() {
        String html = EmailLayout.wrap("Title", "<p>Hi <strong>Jordan</strong>,</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).contains("<strong>Jordan</strong>");
    }

    @Test
    void richWrap_supportReplyFooterInvitesAReply() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.SUPPORT_REPLY, SUPPORT_ADDRESS);

        assertThat(html).contains("reply to this email");
        assertThat(html).doesNotContain("mailto:" + SUPPORT_ADDRESS);
    }

    @Test
    void richWrap_supportLinkFooterPointsToTheGivenAddress() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.SUPPORT_LINK, SUPPORT_ADDRESS);

        assertThat(html).contains("mailto:" + SUPPORT_ADDRESS);
    }

    @Test
    void richWrap_noneFooterOmitsBothTheReplyAndMailtoLines() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("reply to this email");
        assertThat(html).doesNotContain("mailto:");
    }

    @Test
    void richWrap_alwaysIncludesTheLegalLineRegardlessOfFooterChoice() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).contains("Fynora Technovation LLP");
        assertThat(html).contains(String.valueOf(java.time.Year.now().getValue()));
    }

    @Test
    void richWrap_escapesTheHeadingButNotTheBody() {
        String html = EmailLayout.wrap("<script>bad</script>", "<p>trusted</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("<script>bad</script>");
        assertThat(html).contains("&lt;script&gt;bad&lt;/script&gt;");
        assertThat(html).contains("<p>trusted</p>");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=EmailLayoutTest test`
Expected: compile failure — `com.finora.service.EmailLayout` does not exist yet.

- [ ] **Step 3: Create the new `EmailLayout`**

Create `backend/src/main/java/com/finora/service/EmailLayout.java`:

```java
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
```

- [ ] **Step 4: Delete the old `EmailLayout` and its old test, update `EmailNotificationProvider`**

Delete `backend/src/main/java/com/finora/notification/provider/EmailLayout.java` and `backend/src/test/java/com/finora/notification/provider/EmailLayoutTest.java`.

In `backend/src/main/java/com/finora/notification/provider/EmailNotificationProvider.java`, add the import (the class is used unqualified at the current line `String html = EmailLayout.wrap(...)`, which needs no other change since the method signature it calls — the 4-arg legacy overload — is unchanged):

```java
import com.finora.service.EmailLayout;
```

Add this import alongside the existing `com.finora.service.EmailMessage`/`com.finora.service.EmailProvider`/`com.finora.service.EmailResult` imports already in that file.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=EmailLayoutTest,EmailNotificationProviderTest test`
Expected: PASS, all tests including the new rich-overload ones.

- [ ] **Step 6: Run the full backend build to catch any other reference to the old location**

Run: `cd backend && mvn -q compile test-compile`
Expected: BUILD SUCCESS — confirms no other file referenced `com.finora.notification.provider.EmailLayout`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/EmailLayout.java \
        backend/src/test/java/com/finora/service/EmailLayoutTest.java \
        backend/src/main/java/com/finora/notification/provider/EmailNotificationProvider.java
git rm backend/src/main/java/com/finora/notification/provider/EmailLayout.java \
       backend/src/test/java/com/finora/notification/provider/EmailLayoutTest.java
git commit -m "refactor(email): generalize EmailLayout with CTA button + legal footer support"
```

---

## Task 2: Add the 2 new methods to the `EmailProvider` interface

**Files:**
- Modify: `backend/src/main/java/com/finora/service/EmailProvider.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `EmailResult sendStatementReadyEmail(String toEmail, String bankName, String jobId)` and `EmailResult sendStatementHeldEmail(String toEmail)`, implemented in Task 4 (`ResendEmailProvider`) and Task 3 (`NoOpEmailProvider`), called from Task 7 (`StatementStatusNotifier`).

- [ ] **Step 1: Add the 2 method declarations**

In `backend/src/main/java/com/finora/service/EmailProvider.java`, add after `sendInvoiceEmail`'s declaration (the last method in the interface):

```java
    /** Statement-ready and statement-held emails moved off the notification_templates/outbox
     *  system (premium email redesign, 2026-09-11) so they can share {@link EmailLayout}'s
     *  branded wrapper and a CTA button the way the other 10 emails in this interface already do
     *  -- a DB-stored plain-{{placeholder}} template had no way to carry that HTML alongside a
     *  reviewable copy-only row. Called directly by {@code StatementStatusNotifier}, not through
     *  the outbox; PUSH for the same two events is unaffected and still goes through
     *  {@code notification_templates}. Sent from {@link EmailMessage.Sender#SUPPORT} -- a customer
     *  who gets either of these may reasonably want to reply and reach a person, same reasoning
     *  {@code EmailNotificationProvider.SUPPORT_SENDER_TYPES} already documented for these two
     *  types before this move.
     *
     *  @param jobId the import job's id, as a String, for the "Review Statement" button's
     *                {@code /app/imports/{jobId}} deep link. */
    EmailResult sendStatementReadyEmail(String toEmail, String bankName, String jobId);

    /** See {@link #sendStatementReadyEmail}'s own doc for why this exists outside the outbox. No
     *  CTA button -- there is nothing yet to review, only to wait for. */
    EmailResult sendStatementHeldEmail(String toEmail);
```

- [ ] **Step 2: Confirm the build now fails everywhere an implementer is missing**

Run: `cd backend && mvn -q compile 2>&1 | grep -E "ResendEmailProvider|NoOpEmailProvider"`
Expected: compile errors for both classes — "is not abstract and does not override abstract method" — confirming both implementers need the new methods (fixed in Tasks 3 and 4).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/finora/service/EmailProvider.java
git commit -m "feat(email): add sendStatementReadyEmail/sendStatementHeldEmail to EmailProvider"
```

---

## Task 3: Implement the 2 new methods in `NoOpEmailProvider`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/NoOpEmailProvider.java`

**Interfaces:**
- Consumes: `EmailProvider.sendStatementReadyEmail`/`sendStatementHeldEmail` (Task 2).
- Produces: nothing new for later tasks.

- [ ] **Step 1: Add the 2 implementations**

In `backend/src/main/java/com/finora/service/NoOpEmailProvider.java`, add after `sendInvoiceEmail`'s implementation (the last method before `requiredConfigHint`):

```java
    @Override
    public EmailResult sendStatementReadyEmail(String toEmail, String bankName, String jobId) {
        log.info("No email provider configured — would have sent a statement-ready email to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }

    @Override
    public EmailResult sendStatementHeldEmail(String toEmail) {
        log.info("No email provider configured — would have sent a statement-held email to {}", toEmail);
        return EmailResult.failure(ProviderType.RESEND, "No email provider configured");
    }
```

- [ ] **Step 2: Compile**

Run: `cd backend && mvn -q compile 2>&1 | grep NoOpEmailProvider`
Expected: no output (no errors referencing this class).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/finora/service/NoOpEmailProvider.java
git commit -m "feat(email): implement statement email methods in NoOpEmailProvider"
```

---

## Task 4: Rewrite `ResendEmailProvider` — all 12 emails through the new wrapper

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ResendEmailProvider.java` (full rewrite of the 10 existing `send*` methods + 2 new ones; `send(EmailMessage)`, constructor, `fromHeader`, `applyTemplateVariables`, `supportLine` (deleted, replaced by `EmailLayout`), `DEACTIVATED_AT_FORMAT` are otherwise unchanged)
- Create: `backend/src/test/java/com/finora/service/ResendEmailProviderTest.java`

**Interfaces:**
- Consumes: `EmailLayout.wrap(heading, bodyHtml, cta, footer, supportAddress)` (Task 1), `EmailProperties.resolveBaseUrl(String)` (existing), `EmailProperties.getSupportFromAddress()` (existing).
- Produces: package-private `build*Message(...)` helper methods (one per email — `buildPasswordResetMessage`, `buildEmailVerificationMessage`, `buildEmailChangeVerificationMessage`, `buildWelcomeMessage`, `buildPasswordChangedMessage`, `buildAccountDeactivatedMessage`, `buildAccountReactivatedMessage`, `buildAccountDeletedMessage`, `buildSubscriptionActivatedMessage`, `buildInvoiceMessage`, `buildStatementReadyMessage`, `buildStatementHeldMessage`), each returning an `EmailMessage` and unit-tested directly (no HTTP mocking needed since `send(EmailMessage)` itself is unchanged and already trusted).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/finora/service/ResendEmailProviderTest.java`:

```java
package com.finora.service;

import com.finora.config.EmailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ResendEmailProviderTest {

    private ResendEmailProvider provider;

    @BeforeEach
    void setUp() {
        EmailProperties props = new EmailProperties();
        props.setApiKey("test-key");
        props.setFromAddress("noreply@example.test");
        props.setSupportFromAddress("support@example.test");
        props.setBillingFromAddress("billing@example.test");
        props.setAppBaseUrl("https://app.fynora.net");
        provider = new ResendEmailProvider(props);
    }

    @Test
    void passwordReset_hasTheExpectedSubjectButtonAndExpiry() {
        EmailMessage message = provider.buildPasswordResetMessage(
                "user@example.test", "https://app.fynora.net/reset-password?token=abc");

        assertThat(message.subject()).isEqualTo("Reset your Fynora password");
        assertThat(message.html()).contains("https://app.fynora.net/reset-password?token=abc");
        assertThat(message.html()).contains("Reset Password");
        assertThat(message.html()).contains("expires in 30 minutes");
    }

    @Test
    void emailVerification_hasTheExpectedSubjectButtonAndExpiry() {
        EmailMessage message = provider.buildEmailVerificationMessage(
                "user@example.test", "https://app.fynora.net/verify-email?token=abc");

        assertThat(message.subject()).isEqualTo("Verify your email address");
        assertThat(message.html()).contains("https://app.fynora.net/verify-email?token=abc");
        assertThat(message.html()).contains("Verify Email");
        assertThat(message.html()).contains("expires in 24 hours");
    }

    @Test
    void emailChangeVerification_hasTheExpectedSubjectButtonAndExpiry() {
        EmailMessage message = provider.buildEmailChangeVerificationMessage(
                "new@example.test", "https://app.fynora.net/email-change-verify?token=abc");

        assertThat(message.subject()).isEqualTo("Confirm your new email address");
        assertThat(message.html()).contains("https://app.fynora.net/email-change-verify?token=abc");
        assertThat(message.html()).contains("Confirm Email");
        assertThat(message.html()).contains("expires in 15 minutes");
    }

    @Test
    void welcome_greetsByNameAndLinksToTheApp() {
        EmailMessage message = provider.buildWelcomeMessage("user@example.test", "Jordan Lee");

        assertThat(message.subject()).isEqualTo("Welcome to Fynora");
        assertThat(message.html()).contains("Hi Jordan Lee");
        assertThat(message.html()).contains("https://app.fynora.net/app");
        assertThat(message.html()).contains("Open Fynora");
    }

    @Test
    void passwordChanged_linksToAccountSecurity() {
        EmailMessage message = provider.buildPasswordChangedMessage("user@example.test");

        assertThat(message.subject()).isEqualTo("Your Fynora password was changed");
        assertThat(message.html()).contains("https://app.fynora.net/app/settings");
        assertThat(message.html()).contains("Review Account Security");
    }

    @Test
    void accountDeactivated_includesTimestampDeviceAndIp() {
        Instant when = Instant.parse("2026-08-14T20:15:00Z");
        EmailMessage message = provider.buildAccountDeactivatedMessage(
                "user@example.test", when, "Chrome on macOS", "203.0.113.4");

        assertThat(message.subject()).isEqualTo("Your Fynora account was deactivated");
        assertThat(message.html()).contains("14 Aug 2026, 20:15");
        assertThat(message.html()).contains("Chrome on macOS");
        assertThat(message.html()).contains("203.0.113.4");
    }

    @Test
    void accountDeactivated_omitsDeviceAndIpLinesWhenBlank() {
        EmailMessage message = provider.buildAccountDeactivatedMessage(
                "user@example.test", Instant.now(), null, "");

        assertThat(message.html()).doesNotContain("Device:");
        assertThat(message.html()).doesNotContain("IP address:");
    }

    @Test
    void accountReactivated_hasNoButtonAndTheExpectedSubject() {
        EmailMessage message = provider.buildAccountReactivatedMessage("user@example.test");

        assertThat(message.subject()).isEqualTo("Your Fynora account was reactivated");
        assertThat(message.html()).contains("Welcome back");
    }

    @Test
    void accountDeleted_includesTheDeletionTimestamp() {
        Instant when = Instant.parse("2026-08-14T20:15:00Z");
        EmailMessage message = provider.buildAccountDeletedMessage("user@example.test", when);

        assertThat(message.subject()).isEqualTo("Your Fynora account has been deleted");
        assertThat(message.html()).contains("14 Aug 2026, 20:15");
        assertThat(message.html()).contains("cannot be undone");
    }

    @Test
    void subscriptionActivated_namesThePlanAndCadenceAndSendsFromBilling() {
        EmailMessage message = provider.buildSubscriptionActivatedMessage(
                "user@example.test", "Jordan Lee", "Premium", "YEARLY");

        assertThat(message.subject()).isEqualTo("Your Fynora Premium subscription is active");
        assertThat(message.html()).contains("Premium");
        assertThat(message.html()).contains("billed yearly");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.BILLING);
    }

    @Test
    void invoice_linksToBillingAndSendsFromBilling() {
        EmailMessage message = provider.buildInvoiceMessage(
                "user@example.test", "Jordan Lee", "Premium");

        assertThat(message.subject()).isEqualTo("Your Fynora invoice");
        assertThat(message.html()).contains("https://app.fynora.net/app/billing");
        assertThat(message.html()).contains("Open Billing");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.BILLING);
    }

    @Test
    void statementReady_deepLinksToTheSpecificJobAndSendsFromSupport() {
        EmailMessage message = provider.buildStatementReadyMessage(
                "user@example.test", "HDFC Bank", "11111111-1111-1111-1111-111111111111");

        assertThat(message.subject()).isEqualTo("Your HDFC Bank statement is ready");
        assertThat(message.html())
                .contains("https://app.fynora.net/app/imports/11111111-1111-1111-1111-111111111111");
        assertThat(message.html()).contains("Review Statement");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    @Test
    void statementHeld_hasNoButtonAndSendsFromSupport() {
        EmailMessage message = provider.buildStatementHeldMessage("user@example.test");

        assertThat(message.subject()).isEqualTo("We're checking your statement");
        assertThat(message.html()).contains("No action is needed");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }
}
```

Note the `Instant.parse` value above is chosen to match `DEACTIVATED_AT_FORMAT`'s existing rendering ("14 Aug 2026, 20:15") — this is the same fixture value the pre-existing behavior already produces; only the surrounding HTML structure changes in this task, not this formatting.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=ResendEmailProviderTest test`
Expected: compile failure — none of the `build*Message` methods exist yet.

- [ ] **Step 3: Replace the 10 existing `send*` bodies and add the 2 new ones**

In `backend/src/main/java/com/finora/service/ResendEmailProvider.java`, delete the `supportLine()` method (lines 140–154 of the current file) and every `send*` method from `sendPasswordResetEmail` through `sendInvoiceEmail` (current lines 156–276), replacing them with:

```java
    @Override
    public EmailResult sendPasswordResetEmail(String toEmail, String resetLink) {
        return send(buildPasswordResetMessage(toEmail, resetLink));
    }

    EmailMessage buildPasswordResetMessage(String toEmail, String resetLink) {
        String bodyHtml = """
                <p>Someone requested a password reset for your Fynora account.</p>
                <p>Use the button below to choose a new password. This link expires in 30 minutes.</p>
                <p>If you didn't make this request, you can safely ignore this email.</p>
                """;
        String html = EmailLayout.wrap("Reset your password", bodyHtml,
                new EmailLayout.CtaButton("Reset Password", resetLink), EmailLayout.Footer.SUPPORT_LINK,
                emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Reset your Fynora password", html);
    }

    @Override
    public EmailResult sendEmailVerificationEmail(String toEmail, String verifyLink) {
        return send(buildEmailVerificationMessage(toEmail, verifyLink));
    }

    EmailMessage buildEmailVerificationMessage(String toEmail, String verifyLink) {
        String bodyHtml = """
                <p>Verify your email address to finish setting up your Fynora account.</p>
                <p>This verification link expires in 24 hours.</p>
                <p>If you didn't create a Fynora account, you can safely ignore this email.</p>
                """;
        String html = EmailLayout.wrap("Verify your email", bodyHtml,
                new EmailLayout.CtaButton("Verify Email", verifyLink), EmailLayout.Footer.SUPPORT_LINK,
                emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Verify your email address", html);
    }

    @Override
    public EmailResult sendEmailChangeVerificationEmail(String toEmail, String verifyLink) {
        return send(buildEmailChangeVerificationMessage(toEmail, verifyLink));
    }

    EmailMessage buildEmailChangeVerificationMessage(String toEmail, String verifyLink) {
        String bodyHtml = """
                <p>Confirm this email address to complete the email change for your Fynora account.</p>
                <p>This confirmation link expires in 15 minutes.</p>
                <p>If you didn't request this change, you can safely ignore this email. Your account
                email address will not change unless you click the button above.</p>
                """;
        String html = EmailLayout.wrap("Confirm your new email address", bodyHtml,
                new EmailLayout.CtaButton("Confirm Email", verifyLink), EmailLayout.Footer.SUPPORT_LINK,
                emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Confirm your new email address", html);
    }

    @Override
    public EmailResult sendWelcomeEmail(String toEmail, String fullName) {
        return send(buildWelcomeMessage(toEmail, fullName));
    }

    EmailMessage buildWelcomeMessage(String toEmail, String fullName) {
        String bodyHtml = """
                <p>Hi %s,</p>
                <p>Your account is ready.</p>
                <p>Import a bank statement or connect an account to start organizing your finances in
                one place.</p>
                """.formatted(fullName);
        String appUrl = emailProperties.resolveBaseUrl(null) + "/app";
        String html = EmailLayout.wrap("Welcome to Fynora", bodyHtml,
                new EmailLayout.CtaButton("Open Fynora", appUrl), EmailLayout.Footer.SUPPORT_LINK,
                emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Welcome to Fynora", html);
    }

    @Override
    public EmailResult sendPasswordChangedEmail(String toEmail) {
        return send(buildPasswordChangedMessage(toEmail));
    }

    EmailMessage buildPasswordChangedMessage(String toEmail) {
        String bodyHtml = """
                <p>Your Fynora password was changed.</p>
                <p>If you made this change, no further action is needed.</p>
                <p>If this wasn't you, change your password again immediately.</p>
                """;
        String securityUrl = emailProperties.resolveBaseUrl(null) + "/app/settings";
        String html = EmailLayout.wrap("Password changed", bodyHtml,
                new EmailLayout.CtaButton("Review Account Security", securityUrl),
                EmailLayout.Footer.SUPPORT_LINK, emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Your Fynora password was changed", html);
    }

    @Override
    public EmailResult sendAccountDeactivatedEmail(String toEmail, Instant deactivatedAt, String device, String ip) {
        return send(buildAccountDeactivatedMessage(toEmail, deactivatedAt, device, ip));
    }

    EmailMessage buildAccountDeactivatedMessage(String toEmail, Instant deactivatedAt, String device, String ip) {
        // UTC, not the account's own timezone -- this method has no access to it, and a plain,
        // explicitly-labelled UTC timestamp is unambiguous where a bare local-looking one would not be.
        String when = DEACTIVATED_AT_FORMAT.format(deactivatedAt.atZone(ZoneOffset.UTC));
        // Best-effort labels degrade to omission, not a placeholder like "Unknown" that would read
        // as a real (if unhelpful) fact about the request.
        String deviceLine = (device != null && !device.isBlank())
                ? "<p>Device: %s</p>".formatted(device) : "";
        String ipLine = (ip != null && !ip.isBlank())
                ? "<p>IP address: %s</p>".formatted(ip) : "";
        String bodyHtml = """
                <p>Your Fynora account was deactivated on %s (UTC).</p>
                %s%s
                <p>Your data is retained securely. Sign in again at any time to reactivate your account.</p>
                <p>If you didn't do this, act now.</p>
                """.formatted(when, deviceLine, ipLine);
        String html = EmailLayout.wrap("Account deactivated", bodyHtml, null,
                EmailLayout.Footer.SUPPORT_LINK, emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Your Fynora account was deactivated", html);
    }

    @Override
    public EmailResult sendAccountReactivatedEmail(String toEmail) {
        return send(buildAccountReactivatedMessage(toEmail));
    }

    EmailMessage buildAccountReactivatedMessage(String toEmail) {
        String bodyHtml = """
                <p>Your Fynora account has been reactivated.</p>
                <p>If you didn't do this, act now.</p>
                """;
        String html = EmailLayout.wrap("Welcome back", bodyHtml, null,
                EmailLayout.Footer.SUPPORT_LINK, emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Your Fynora account was reactivated", html);
    }

    @Override
    public EmailResult sendAccountDeletedEmail(String toEmail, Instant deletedAt) {
        return send(buildAccountDeletedMessage(toEmail, deletedAt));
    }

    EmailMessage buildAccountDeletedMessage(String toEmail, Instant deletedAt) {
        String when = DEACTIVATED_AT_FORMAT.format(deletedAt.atZone(ZoneOffset.UTC));
        String bodyHtml = """
                <p>Your Fynora account and all your data were permanently deleted on %s (UTC).</p>
                <p>This cannot be undone.</p>
                <p>If you didn't do this, contact us immediately.</p>
                """.formatted(when);
        String html = EmailLayout.wrap("Account deleted", bodyHtml, null,
                EmailLayout.Footer.SUPPORT_LINK, emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Your Fynora account has been deleted", html);
    }

    @Override
    public EmailResult sendSubscriptionActivatedEmail(String toEmail, String fullName, String planName, String billingCycle) {
        return send(buildSubscriptionActivatedMessage(toEmail, fullName, planName, billingCycle));
    }

    EmailMessage buildSubscriptionActivatedMessage(String toEmail, String fullName, String planName, String billingCycle) {
        String cadence = "YEARLY".equals(billingCycle) ? "yearly" : "monthly";
        String bodyHtml = """
                <p>Hi %s,</p>
                <p>Your Fynora <strong>%s</strong> subscription is now active, billed %s.</p>
                <p>You can review your plan, payment history, or make changes any time from Billing in
                the app.</p>
                <p>Questions about this charge? Just reply to this email.</p>
                """.formatted(fullName, planName, cadence);
        String html = EmailLayout.wrap("Subscription active", bodyHtml, null,
                EmailLayout.Footer.NONE, emailProperties.getSupportFromAddress());
        EmailMessage plain = EmailMessage.html(toEmail, "Your Fynora " + planName + " subscription is active", html);
        return new EmailMessage(plain.to(), plain.subject(), plain.html(), plain.text(),
                plain.attachments(), plain.templateVariables(), EmailMessage.Sender.BILLING);
    }

    @Override
    public EmailResult sendInvoiceEmail(String toEmail, String fullName, String planName, EmailAttachment invoicePdf) {
        EmailMessage message = buildInvoiceMessage(toEmail, fullName, planName);
        return send(new EmailMessage(message.to(), message.subject(), message.html(), message.text(),
                List.of(invoicePdf), message.templateVariables(), message.sender()));
    }

    EmailMessage buildInvoiceMessage(String toEmail, String fullName, String planName) {
        String bodyHtml = """
                <p>Hi %s,</p>
                <p>Thank you for your payment. Your invoice for the Fynora <strong>%s</strong> plan is
                attached.</p>
                <p>You can also view or download it any time from Billing in the app.</p>
                """.formatted(fullName, planName);
        String billingUrl = emailProperties.resolveBaseUrl(null) + "/app/billing";
        String html = EmailLayout.wrap("Payment received", bodyHtml,
                new EmailLayout.CtaButton("Open Billing", billingUrl), EmailLayout.Footer.NONE,
                emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Your Fynora invoice", html, EmailMessage.Sender.BILLING);
    }

    @Override
    public EmailResult sendStatementReadyEmail(String toEmail, String bankName, String jobId) {
        return send(buildStatementReadyMessage(toEmail, bankName, jobId));
    }

    EmailMessage buildStatementReadyMessage(String toEmail, String bankName, String jobId) {
        String bodyHtml = """
                <p>We've finished the additional checks on your %s statement.</p>
                <p>It's now ready for review and import in Fynora.</p>
                """.formatted(bankName);
        String statementUrl = emailProperties.resolveBaseUrl(null) + "/app/imports/" + jobId;
        String html = EmailLayout.wrap("Statement ready", bodyHtml,
                new EmailLayout.CtaButton("Review Statement", statementUrl),
                EmailLayout.Footer.SUPPORT_REPLY, emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "Your " + bankName + " statement is ready", html,
                EmailMessage.Sender.SUPPORT);
    }

    @Override
    public EmailResult sendStatementHeldEmail(String toEmail) {
        return send(buildStatementHeldMessage(toEmail));
    }

    EmailMessage buildStatementHeldMessage(String toEmail) {
        String bodyHtml = """
                <p>We need to run some additional checks on your statement before we can complete the
                import.</p>
                <p>No action is needed from you right now.</p>
                <p>We'll notify you once it's ready.</p>
                """;
        String html = EmailLayout.wrap("We're checking your statement", bodyHtml, null,
                EmailLayout.Footer.SUPPORT_REPLY, emailProperties.getSupportFromAddress());
        return EmailMessage.html(toEmail, "We're checking your statement", html, EmailMessage.Sender.SUPPORT);
    }
```

`EmailMessage.html(String, String, String, EmailMessage.Sender)` (4-arg, with sender) already exists (`EmailMessage.java:52`) — used directly by `buildStatementReadyMessage`/`buildStatementHeldMessage` and `buildInvoiceMessage` above; `buildSubscriptionActivatedMessage` builds the plain 3-arg message first and re-wraps it with `Sender.BILLING` since there's no 4-arg overload taking positional fields other than the constructor itself — this matches the record's own field order exactly, so double check the field order against `EmailMessage.java:14-22` (`to, subject, html, text, attachments, templateVariables, sender`) while implementing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=ResendEmailProviderTest test`
Expected: PASS, all 14 tests.

- [ ] **Step 5: Full backend compile**

Run: `cd backend && mvn -q compile test-compile 2>&1 | tail -40`
Expected: BUILD SUCCESS. (`ResendEmailProvider` and `NoOpEmailProvider` were the only 2 `EmailProvider` implementers — Task 3 already covered the other.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/ResendEmailProvider.java \
        backend/src/test/java/com/finora/service/ResendEmailProviderTest.java
git commit -m "feat(email): redesign all 12 transactional emails with the branded wrapper and CTA buttons"
```

---

## Task 5: `ImportJob` gains a held-email idempotency column

**Files:**
- Create: `backend/src/main/resources/db/migration/V190__import_job_statement_held_email_sent.sql`
- Modify: `backend/src/main/java/com/finora/entity/ImportJob.java`
- Create: `backend/src/test/java/com/finora/imports/migration/V190ImportJobStatementHeldEmailSentMigrationIT.java`

**Interfaces:**
- Produces: `ImportJob.markStatementHeldEmailSent(Instant now)` — returns `true` the first time it's called for a given job, `false` on every call after. `StatementStatusNotifier` (Task 7) calls this to decide whether to actually send the held email.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/imports/migration/V190ImportJobStatementHeldEmailSentMigrationIT.java`:

```java
package com.finora.imports.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.repository.ImportJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V190ImportJobStatementHeldEmailSentMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private ImportJobRepository repository;

    @Test
    void newColumnDefaultsToNullAndCanBeSetOnce() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
        job = repository.save(job);

        assertThat(repository.findById(job.getId()).orElseThrow().markStatementHeldEmailSent(Instant.now()))
                .as("first call marks it sent and returns true")
                .isTrue();
        repository.save(job);

        ImportJob reloaded = repository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.markStatementHeldEmailSent(Instant.now()))
                .as("a second call must not report sent again")
                .isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -q -Dtest=V190ImportJobStatementHeldEmailSentMigrationIT test`
Expected: compile failure — `ImportJob.markStatementHeldEmailSent` does not exist yet.

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V190__import_job_statement_held_email_sent.sql`:

```sql
-- Backs StatementStatusNotifier's own no-double-send guarantee for the statement-held email
-- (premium email redesign, 2026-09-11), which moved off notification_templates/the outbox and so
-- lost the outbox's own notification_key-based idempotency for its email leg. The outbox's
-- guarantee was real and tested (ImportJobWorkerTest.aJobHeldAgainAfterAFailedReprocessReusesThe
-- SameNotificationKey): a job reprocessed after holding, that fails the same way and holds again,
-- must not receive a second "we're checking your statement" email. This column replaces that
-- guarantee at the entity level instead of dropping it.
--
-- Mirrors was_held_for_review (V134): set once, never cleared, including by a reprocess -- a job
-- that already got the email once must not get it again no matter how many more times it holds.
--
-- DEFAULT NULL leaves every existing row unsent, which is correct: no job predating this migration
-- has had this email sent through the new direct path (it went through the outbox instead, whose
-- own history is unaffected by this migration).
ALTER TABLE import_jobs
    ADD COLUMN statement_held_email_sent_at TIMESTAMPTZ;

COMMENT ON COLUMN import_jobs.statement_held_email_sent_at IS
    'When the statement-held email was sent for this job, or NULL if never. Set once via '
    'ImportJob.markStatementHeldEmailSent and never cleared -- see V190 for why this exists '
    'outside the notification outbox.';
```

- [ ] **Step 4: Add the field and method to `ImportJob`**

In `backend/src/main/java/com/finora/entity/ImportJob.java`, add the field immediately after `wasHeldForReview` (current lines 243–244):

```java
    /**
     * When the statement-held email was sent for this job, or {@code null} if never. Set once via
     * {@link #markStatementHeldEmailSent} and never cleared, including by {@link
     * #returnToQueueForReprocess} -- mirrors {@link #wasHeldForReview}'s own "never cleared"
     * reasoning, for the same purpose: a job reprocessed after holding, that fails the same way
     * and holds again, must not receive a second "we're checking your statement" email. See V190
     * for why this exists outside the notification outbox.
     */
    @Column(name = "statement_held_email_sent_at")
    private Instant statementHeldEmailSentAt;
```

Add the method near `holdForReview` (current line 558) and the `wasHeldForReview()` getter (current line 750) — place it directly after `wasHeldForReview()`:

```java
    /**
     * True, and marks the email sent, the first time this is called for this job; false on every
     * call after. The check and the set happen together so a caller never has to coordinate a
     * separate read-then-write itself -- see V190's own migration comment for why this exists.
     */
    public boolean markStatementHeldEmailSent(Instant now) {
        if (statementHeldEmailSentAt != null) {
            return false;
        }
        statementHeldEmailSentAt = now;
        return true;
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=V190ImportJobStatementHeldEmailSentMigrationIT test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V190__import_job_statement_held_email_sent.sql \
        backend/src/main/java/com/finora/entity/ImportJob.java \
        backend/src/test/java/com/finora/imports/migration/V190ImportJobStatementHeldEmailSentMigrationIT.java
git commit -m "feat(imports): add ImportJob column to guarantee the held-statement email sends only once"
```

---

## Task 6: Deactivate the DB-template EMAIL rows for the 2 statement types

**Files:**
- Create: `backend/src/main/resources/db/migration/V191__deactivate_statement_email_templates.sql`
- Create: `backend/src/test/java/com/finora/notification/migration/V191DeactivateStatementEmailTemplatesMigrationIT.java`

**Interfaces:**
- Consumes: `NotificationTemplateRepository.findByTypeAndChannelAndActiveTrue(NotificationType, NotificationChannel)` (existing).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/notification/migration/V191DeactivateStatementEmailTemplatesMigrationIT.java`:

```java
package com.finora.notification.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class V191DeactivateStatementEmailTemplatesMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationTemplateRepository repository;

    @Test
    void theEmailRowsForBothStatementTypesAreNoLongerActive() {
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_READY, NotificationChannel.EMAIL)).isEmpty();
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_HELD, NotificationChannel.EMAIL)).isEmpty();
    }

    @Test
    void thePushRowsForBothStatementTypesAreStillActive() {
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_READY, NotificationChannel.PUSH)).isPresent();
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_HELD, NotificationChannel.PUSH)).isPresent();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -q -Dtest=V191DeactivateStatementEmailTemplatesMigrationIT test`
Expected: FAIL on `theEmailRowsForBothStatementTypesAreNoLongerActive` — the rows are still active (V136 last touched them).

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V191__deactivate_statement_email_templates.sql`:

```sql
-- Moves IMPORT_STATEMENT_READY/IMPORT_STATEMENT_HELD's EMAIL copy off the notification_templates
-- system (premium email redesign, 2026-09-11). ResendEmailProvider now sends both emails directly
-- (sendStatementReadyEmail/sendStatementHeldEmail), through StatementStatusNotifier, sharing the
-- same branded EmailLayout wrapper, CTA button, and legal footer every other transactional email
-- uses -- a DB-stored plain-{{placeholder}} row had no way to carry that HTML alongside copy that
-- stays reviewable in one place.
--
-- PUSH rows for both types are untouched: push has no HTML-wrapper concern and stays on the
-- existing outbox/DB-template path.
--
-- UPDATE (deactivate), not DELETE, per idx_notification_templates_active's own column comment
-- (V127): both rows have real production renders behind them -- these two email types have been
-- live since V136/V155 -- unlike V136's own UPDATE-in-place of these same rows, which had zero
-- past renders to protect at the time.
UPDATE notification_templates
   SET active = false
 WHERE type IN ('IMPORT_STATEMENT_READY', 'IMPORT_STATEMENT_HELD')
   AND channel = 'EMAIL'
   AND active = true;
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && mvn -q -Dtest=V191DeactivateStatementEmailTemplatesMigrationIT test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V191__deactivate_statement_email_templates.sql \
        backend/src/test/java/com/finora/notification/migration/V191DeactivateStatementEmailTemplatesMigrationIT.java
git commit -m "feat(notification): deactivate the DB-template EMAIL rows for statement ready/held"
```

---

## Task 7: `StatementStatusNotifier` — the single call site for both statement events

**Files:**
- Create: `backend/src/main/java/com/finora/service/StatementStatusNotifier.java`
- Create: `backend/src/test/java/com/finora/service/StatementStatusNotifierTest.java`

**Interfaces:**
- Consumes: `NotificationService.request(NotificationRequest)` (existing), `EmailProvider.sendStatementReadyEmail`/`sendStatementHeldEmail` (Task 2/4), `ImportJobRepository.save(ImportJob)` (existing), `ImportJob.markStatementHeldEmailSent(Instant)` (Task 5), `UserRepository.findById(UUID)` (existing), `AuditService.recordEvenOnRollback` (existing), `AfterCommit.run(String, Runnable)` (existing).
- Produces: `void notifyReady(ImportJob job, String bankName)` and `void notifyHeld(ImportJob job)`, called from Task 8 (`ImportJobWorker`) and Task 9 (`HeldStatementService`).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/finora/service/StatementStatusNotifierTest.java`:

```java
package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StatementStatusNotifierTest {

    private NotificationService notificationService;
    private UserRepository userRepository;
    private ImportJobRepository importJobRepository;
    private EmailProvider emailProvider;
    private AuditService auditService;
    private StatementStatusNotifier notifier;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        emailProvider = mock(EmailProvider.class);
        auditService = mock(AuditService.class);
        notifier = new StatementStatusNotifier(notificationService, userRepository,
                importJobRepository, emailProvider, auditService);

        when(importJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ImportJob job() {
        return new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
    }

    private User userWithEmail(String email) {
        User user = mock(User.class);
        when(user.getEmail()).thenReturn(email);
        when(user.isDeleted()).thenReturn(false);
        return user;
    }

    @Test
    void notifyReady_requestsPushOnly() {
        ImportJob job = job();
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(userWithEmail("user@example.test")));
        when(emailProvider.sendStatementReadyEmail(any(), any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyReady(job, "HDFC Bank");

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        NotificationRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_READY);
        assertThat(sent.channels()).containsExactly(NotificationChannel.PUSH);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_READY_" + job.getId());
        assertThat(sent.params()).containsEntry("bank", "HDFC Bank");
    }

    @Test
    void notifyReady_sendsTheBrandedEmailToTheUsersAddress() {
        ImportJob job = job();
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(userWithEmail("user@example.test")));
        when(emailProvider.sendStatementReadyEmail("user@example.test", "HDFC Bank", job.getId().toString()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyReady(job, "HDFC Bank");

        verify(emailProvider).sendStatementReadyEmail("user@example.test", "HDFC Bank", job.getId().toString());
    }

    @Test
    void notifyReady_skipsTheEmailWhenTheUserHasNoAddressOnFile() {
        ImportJob job = job();
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(userWithEmail("")));

        notifier.notifyReady(job, "HDFC Bank");

        verify(emailProvider, never()).sendStatementReadyEmail(any(), any(), any());
    }

    @Test
    void notifyHeld_requestsPushOnly() {
        ImportJob job = job();
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(userWithEmail("user@example.test")));
        when(emailProvider.sendStatementHeldEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyHeld(job);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        NotificationRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(NotificationType.IMPORT_STATEMENT_HELD);
        assertThat(sent.channels()).containsExactly(NotificationChannel.PUSH);
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_HELD_" + job.getId());
    }

    @Test
    void notifyHeld_sendsTheEmailTheFirstTime() {
        ImportJob job = job();
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(userWithEmail("user@example.test")));
        when(emailProvider.sendStatementHeldEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        notifier.notifyHeld(job);

        verify(emailProvider).sendStatementHeldEmail("user@example.test");
        verify(importJobRepository).save(job);
    }

    @Test
    void notifyHeld_doesNotSendTheEmailASecondTimeForTheSameJob() {
        ImportJob job = job();
        job.markStatementHeldEmailSent(Instant.now());
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(userWithEmail("user@example.test")));

        notifier.notifyHeld(job);

        verify(emailProvider, never()).sendStatementHeldEmail(any());
    }

    @Test
    void notifyHeld_stillRequestsThePushOnARepeatHold() {
        // PUSH keeps its own outbox-level dedup (notification_key) -- this notifier must still
        // ask for it every time; the outbox, not this class, is what absorbs the duplicate.
        ImportJob job = job();
        job.markStatementHeldEmailSent(Instant.now());
        when(userRepository.findById(job.getUserId())).thenReturn(Optional.of(userWithEmail("user@example.test")));

        notifier.notifyHeld(job);

        verify(notificationService).request(any());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=StatementStatusNotifierTest test`
Expected: compile failure — `StatementStatusNotifier` does not exist yet.

- [ ] **Step 3: Implement `StatementStatusNotifier`**

Create `backend/src/main/java/com/finora/service/StatementStatusNotifier.java`:

```java
package com.finora.service;

import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.util.AfterCommit;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * The single call site for "tell the user their statement's status changed" -- PUSH stays on the
 * existing notification outbox (unaffected by the premium email redesign, 2026-09-11); the email
 * leg is sent directly through {@link EmailProvider}, bypassing {@code notification_templates}
 * entirely (see V191's migration comment for why). Replaces logic that used to be duplicated
 * inline at 4 call sites across {@code ImportJobWorker} and {@code HeldStatementService}.
 *
 * <p>The held-email leg is guarded by {@link ImportJob#markStatementHeldEmailSent} so a job that
 * reprocesses, fails the same way, and re-holds does not get a second "we're checking your
 * statement" email -- see V190's migration comment for why this guarantee needed a new column
 * once the outbox's own idempotency stopped covering the email leg. The ready-email leg needs no
 * equivalent guard: both its call sites are already naturally single-fire (a job reaches
 * {@code COMPLETED} once; {@code HeldStatementService.approve} is gated by
 * {@code refuseIfResolved}), verified by reading both call sites before this class was written.
 */
@Service
public class StatementStatusNotifier {

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final ImportJobRepository importJobRepository;
    private final EmailProvider emailProvider;
    private final AuditService auditService;

    public StatementStatusNotifier(NotificationService notificationService, UserRepository userRepository,
            ImportJobRepository importJobRepository, EmailProvider emailProvider, AuditService auditService) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.importJobRepository = importJobRepository;
        this.emailProvider = emailProvider;
        this.auditService = auditService;
    }

    /**
     * @param bankName the parser's own detected bank name, or the template's documented "bank"
     *                 fallback when none was detected -- the caller's responsibility, same as it
     *                 was before this class existed.
     */
    public void notifyReady(ImportJob job, String bankName) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_READY_" + job.getId(),
                Set.of(NotificationChannel.PUSH),
                Map.of("bank", bankName)));

        emailForUser(job.getUserId(), "statement_ready",
                user -> emailProvider.sendStatementReadyEmail(user.getEmail(), bankName, job.getId().toString()));
    }

    public void notifyHeld(ImportJob job) {
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_HELD,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_HELD_" + job.getId(),
                Set.of(NotificationChannel.PUSH),
                Map.of()));

        if (!job.markStatementHeldEmailSent(Instant.now())) {
            return;
        }
        importJobRepository.save(job);

        emailForUser(job.getUserId(), "statement_held",
                user -> emailProvider.sendStatementHeldEmail(user.getEmail()));
    }

    /** Same guard EmailNotificationProvider.send already applies before handing a user to the
     *  real provider: no user row, a purged account, or a blank email address all skip silently
     *  rather than sending to a stale or synthetic address. Deferred past commit (BH-016): this is
     *  a real network call and must not hold a pooled DB connection, nor fire for an event whose
     *  transaction then rolls back. */
    private void emailForUser(UUID userId, String type, Function<User, EmailResult> send) {
        AfterCommit.run(type + " email", () -> {
            Optional<User> user = userRepository.findById(userId);
            if (user.isEmpty() || user.get().isDeleted()
                    || user.get().getEmail() == null || user.get().getEmail().isBlank()) {
                return;
            }
            EmailResult result = send.apply(user.get());
            auditService.recordEvenOnRollback(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", type, "provider", result.provider().name(), "success", result.success()));
        });
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=StatementStatusNotifierTest test`
Expected: PASS, all 7 tests. (`AfterCommit.run` executes immediately when there's no ambient Spring transaction — true in this plain-Mockito unit test — so the email assertions observe the send synchronously; see `AfterCommit`'s own doc comment.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/StatementStatusNotifier.java \
        backend/src/test/java/com/finora/service/StatementStatusNotifierTest.java
git commit -m "feat(notification): add StatementStatusNotifier as the single statement-email call site"
```

---

## Task 8: Wire `StatementStatusNotifier` into `ImportJobWorker`

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java`
- Modify: `backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java`

**Interfaces:**
- Consumes: `StatementStatusNotifier.notifyReady(ImportJob, String)` / `.notifyHeld(ImportJob)` (Task 7).

- [ ] **Step 1: Update the production code**

In `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java`:

Remove these 6 imports (lines 14–19 of the current file — confirmed unused anywhere else in this file):

```java
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
```

Add:

```java
import com.finora.service.StatementStatusNotifier;
```

Replace the field (current line 139) and its constructor parameter/assignment (current lines 154 and 165):

```java
    private final NotificationService notificationService;
```
→
```java
    private final StatementStatusNotifier statementStatusNotifier;
```

```java
                            NotificationService notificationService,
```
→
```java
                            StatementStatusNotifier statementStatusNotifier,
```

```java
        this.notificationService = notificationService;
```
→
```java
        this.statementStatusNotifier = statementStatusNotifier;
```

Replace `notifyIfPreviouslyHeld` (current lines 598–615, i.e. the full body from `if (!job.wasHeldForReview())` through the closing brace) with:

```java
    private void notifyIfPreviouslyHeld(ImportJob job, String bankName) {
        if (!job.wasHeldForReview()) {
            return;
        }
        statementStatusNotifier.notifyReady(job,
                bankName == null || bankName.isBlank() ? "bank" : bankName);
    }
```

Replace `notifyHeldForReview` (current lines 636–645) with:

```java
    private void notifyHeldForReview(ImportJob job) {
        statementStatusNotifier.notifyHeld(job);
    }
```

- [ ] **Step 2: Update the existing tests**

In `backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java`:

Replace the field declaration and mock setup (current line 59 area, `private NotificationService notificationService;` and its `mock(NotificationService.class)` in `setUp`) with a `StatementStatusNotifier` mock:

```java
    private StatementStatusNotifier statementStatusNotifier;
```

```java
        statementStatusNotifier = mock(StatementStatusNotifier.class);
```

Add the import:

```java
import com.finora.service.StatementStatusNotifier;
```

Remove the now-unused `NotificationChannel`/`NotificationCategory`/`NotificationPriority`/`NotificationRequest`/`NotificationType`/`NotificationService` imports if this test file has no other use of them — check with `grep -n "NotificationChannel\|NotificationCategory\|NotificationPriority\|NotificationRequest\|NotificationType\|NotificationService" backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java` after the edits below; keep any that are still referenced (e.g. `NotificationType.IMPORT_STATEMENT_READY` is still used in assertions below, so `NotificationType`'s import stays).

Update the constructor call (current line 84):

```java
        worker = new ImportJobWorker(jobStore, importService, statementContentService, observability,
                stageRecorder, new ExceptionClassifier(), notificationService, verificationRecorder,
                heldStatementService, new ParserVersionProvider(), heldItemAdminAlertService);
```
→
```java
        worker = new ImportJobWorker(jobStore, importService, statementContentService, observability,
                stageRecorder, new ExceptionClassifier(), statementStatusNotifier, verificationRecorder,
                heldStatementService, new ParserVersionProvider(), heldItemAdminAlertService);
```

Replace `aPreviouslyHeldJobThatCompletesNotifiesTheUserOnPushAndEmail` (current lines 562–603) — rename it to reflect what it now verifies (the notifier delegation, not the raw notification request), body:

```java
    @Test
    void aPreviouslyHeldJobThatCompletesNotifiesTheStatementReady() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenReturn(staged());

        worker.drainOnce();
        runAnotherPass();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);

        // The parser gap is fixed and an admin reprocesses the job.
        job.returnToQueueForReprocess(Instant.now());
        runAnotherPass();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        verify(statementStatusNotifier).notifyReady(job, "HDFC Bank");
    }
```

Replace `anUnidentifiedBankFallsBackToWordingThatStillReads` (current lines 612–633):

```java
    @Test
    void anUnidentifiedBankFallsBackToWordingThatStillReads() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenThrow(new IllegalStateException("no header row found"))
                .thenReturn(staged(null));

        worker.drainOnce();
        runAnotherPass();
        job.returnToQueueForReprocess(Instant.now());
        runAnotherPass();

        verify(statementStatusNotifier).notifyReady(job, "bank");
    }
```

Replace `anOrdinaryImportThatSucceedsFirstTimeNotifiesNobody`'s assertion (current line 643):

```java
        verify(notificationService, never()).request(any());
```
→
```java
        verifyNoInteractions(statementStatusNotifier);
```

Replace `aHeldJobNotifiesTheUserOnceWhenItHolds` (current lines 653–672):

```java
    @Test
    void aHeldJobNotifiesTheUserOnceWhenItHolds() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"));

        worker.drainOnce();
        runAnotherPass();

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        verify(statementStatusNotifier).notifyHeld(job);
    }
```

Replace `aJobHeldAgainAfterAFailedReprocessReusesTheSameNotificationKey` (current lines 684–705) — this guarantee moved into `StatementStatusNotifierTest.notifyHeld_doesNotSendTheEmailASecondTimeForTheSameJob` (Task 7) and `ImportJob`'s own column (Task 5); this test now just proves the worker still asks the notifier every time a hold happens, which is the precondition those other tests depend on:

```java
    @Test
    void aJobHeldAgainAfterAFailedReprocessCallsNotifyHeldEachTime() throws IOException {
        when(importService.parseAndStageWithSession(any(), any(), any()))
                .thenThrow(new IllegalStateException("no header row found"));

        worker.drainOnce();
        runAnotherPass();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);

        job.returnToQueueForReprocess(Instant.now());
        runAnotherPass();
        runAnotherPass();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);

        verify(statementStatusNotifier, times(2)).notifyHeld(job);
    }
```

Replace `aTrustHeldImportNotifiesNobody`'s assertion (current line 818):

```java
        verify(notificationService, never()).request(any());
```
→
```java
        verifyNoInteractions(statementStatusNotifier);
```

- [ ] **Step 2: Run the tests**

Run: `cd backend && mvn -q -Dtest=ImportJobWorkerTest test`
Expected: PASS, all tests in the class.

- [ ] **Step 3: Full backend compile**

Run: `cd backend && mvn -q compile test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java \
        backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java
git commit -m "refactor(imports): route ImportJobWorker's statement notifications through StatementStatusNotifier"
```

---

## Task 9: Wire `StatementStatusNotifier` into `HeldStatementService`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/HeldStatementService.java`
- Modify: `backend/src/test/java/com/finora/service/HeldStatementServiceTest.java`

**Interfaces:**
- Consumes: `StatementStatusNotifier.notifyReady(ImportJob, String)` / `.notifyHeld(ImportJob)` (Task 7).

- [ ] **Step 1: Update the production code**

In `backend/src/main/java/com/finora/service/HeldStatementService.java`:

Remove these 6 imports (lines 27–32 of the current file — confirmed unused anywhere else in this file):

```java
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
```

`StatementStatusNotifier` is already in this file's own package (`com.finora.service`), so no new import is needed for it.

Replace the field (current line 81) and its constructor parameter/assignment (current lines 95 and 108):

```java
    private final NotificationService notificationService;
```
→
```java
    private final StatementStatusNotifier statementStatusNotifier;
```

```java
                                NotificationService notificationService,
```
→
```java
                                StatementStatusNotifier statementStatusNotifier,
```

```java
        this.notificationService = notificationService;
```
→
```java
        this.statementStatusNotifier = statementStatusNotifier;
```

In `openHold` (current lines 166–173), replace:

```java
        notificationService.request(NotificationRequest.of(
                job.getUserId(),
                NotificationType.IMPORT_STATEMENT_HELD,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "IMPORT_HELD_" + job.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                Map.of()));
```
→
```java
        statementStatusNotifier.notifyHeld(job);
```

Replace the private `notifyStatementReady` method (current lines 629–638) with:

```java
    private void notifyStatementReady(ImportJob job) {
        // "bank" is the template's documented fallback, giving "Your bank statement is ready" --
        // this call site has no parser-detected name available, the same reason it never has.
        statementStatusNotifier.notifyReady(job, "bank");
    }
```

- [ ] **Step 2: Update the existing tests**

In `backend/src/test/java/com/finora/service/HeldStatementServiceTest.java`:

Replace the field declaration and mock setup (current line 41 area) with a `StatementStatusNotifier` mock:

```java
    private StatementStatusNotifier statementStatusNotifier;
```

```java
        statementStatusNotifier = mock(StatementStatusNotifier.class);
```

Update the constructor call (current lines 63–66):

```java
        service = new HeldStatementService(repository, eventRepository, idGenerator, importJobRepository,
                findingRepository, auditService, notificationService, importSessionService,
                new ObjectMapper(), statementContentService, importService, parserVersionProvider,
                heldItemAdminAlertService);
```
→
```java
        service = new HeldStatementService(repository, eventRepository, idGenerator, importJobRepository,
                findingRepository, auditService, statementStatusNotifier, importSessionService,
                new ObjectMapper(), statementContentService, importService, parserVersionProvider,
                heldItemAdminAlertService);
```

Replace `createHold_notifiesTheUserTheStatementIsHeld` (current lines 93–111):

```java
    @Test
    void createHold_notifiesTheUserTheStatementIsHeld() {
        ImportJob job = job();
        StagedForJob staged = new StagedForJob(UUID.randomUUID(), 5, 5, "HDFC Bank", List.of(), List.of());

        service.createHold(job, staged, periodIntegrityDecision(), "abc123");

        verify(statementStatusNotifier).notifyHeld(job);
    }
```

Replace `createHold_doesNotAlertASecondTime_whenAHoldAlreadyExistsForThisJob`'s last assertion (current line 127):

```java
        verify(notificationService, never()).request(any());
```
→
```java
        verifyNoInteractions(statementStatusNotifier);
```

Remove the now-unused `notificationService` field's own leftover import if `NotificationService`/`NotificationRequest`/`NotificationChannel` etc. are no longer referenced anywhere else in this test file (check with the same `grep` approach as Task 8's Step 2), and add:

```java
import com.finora.service.StatementStatusNotifier;
```

(Only needed if this test class lives in a different package than `com.finora.service` — confirm the test file's own `package` declaration; if it's already `package com.finora.service;`, no new import is needed, matching the production file.)

- [ ] **Step 3: Run the tests**

Run: `cd backend && mvn -q -Dtest=HeldStatementServiceTest test`
Expected: PASS, all tests in the class.

- [ ] **Step 4: Full backend build**

Run: `cd backend && mvn -q compile test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the full backend test suite**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS — no other file in the codebase references the old `ImportJobWorker`/`HeldStatementService` constructor shapes or the deleted `EmailLayout` location (Task 1's Step 6 already partially confirmed this; this is the final, whole-suite confirmation after every task).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/HeldStatementService.java \
        backend/src/test/java/com/finora/service/HeldStatementServiceTest.java
git commit -m "refactor(imports): route HeldStatementService's statement notifications through StatementStatusNotifier"
```

---

## Self-Review Notes

**Spec coverage:**
- Shared wrapper, consistent logo/spacing/button style across all 12 emails → Task 1 (wrapper) + Task 4 (all 12 wired to it).
- Legal footer with verified entity/address → Task 1 (`LEGAL_ENTITY` constant + `richWrap_alwaysIncludesTheLegalLineRegardlessOfFooterChoice` test).
- New copy exactly as approved (subjects, body wording, button labels) → Task 4.
- Real CTA routes, not invented ones → Task 4 (`/app`, `/app/settings`, `/app/billing`, `/app/imports/{jobId}` — all verified against `frontend/src/App.tsx` before writing this plan).
- Statement emails moved off the DB-template system → Tasks 2, 3, 4 (new methods), 6 (deactivation migration).
- Statement-ready CTA deep-links to the specific job → Task 4 (`buildStatementReadyMessage`'s `jobId` param), Task 8/9 (both call sites pass `job` through).
- No regression to the held-email's existing no-double-send guarantee → Task 5 (new column) + Task 7 (`StatementStatusNotifier` enforces it) + Task 8 (test renamed but guarantee's precondition re-verified, since the actual dedup assertion moved to `StatementStatusNotifierTest`).

**Placeholder scan:** none found — every step above contains complete, copy-pasteable code or an exact shell command.

**Type consistency:** `EmailLayout.CtaButton`/`EmailLayout.Footer` (Task 1) are used with identical names and signatures in Task 4; `StatementStatusNotifier.notifyReady(ImportJob, String)`/`.notifyHeld(ImportJob)` (Task 7) match exactly what Tasks 8 and 9 call; `EmailProvider.sendStatementReadyEmail(String, String, String)`/`sendStatementHeldEmail(String)` (Task 2) match their implementations in Tasks 3 and 4 and their call sites in Task 7.
