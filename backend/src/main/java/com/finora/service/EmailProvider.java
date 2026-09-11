package com.finora.service;

import java.time.Instant;

/**
 * Abstraction over "actually send an email" so business services never talk to Resend (or any
 * future replacement) directly -- same PhoneVerificationProvider-style boundary. isConfigured()
 * is what lets AuthService.forgotPassword() decide whether it's safe to omit the reset link from
 * the API response (real email exists, so leaking the link in the response body would be a
 * needless exposure) or whether it needs to fall back to returning the link directly (no email
 * provider configured -- a dev-environment convenience, not a production posture).
 *
 * send() is the generic entry point (HTML/plaintext/attachments/template variables -- see
 * EmailMessage); sendPasswordResetEmail/sendWelcomeEmail/sendPasswordChangedEmail are the actual
 * purpose-built emails this app sends today, each building its own EmailMessage internally so
 * callers never construct one by hand for a well-known email type.
 */
public interface EmailProvider {
    boolean isConfigured();

    EmailResult send(EmailMessage message);

    EmailResult sendPasswordResetEmail(String toEmail, String resetLink);
    EmailResult sendWelcomeEmail(String toEmail, String fullName);
    /** D-23. Sent at registration, and again if a Google sign-in later finds a matching but
     *  not-yet-verified account (see AuthService.loginWithGoogle) -- the same link either way,
     *  since verifying is the same action regardless of which flow asked for it. */
    EmailResult sendEmailVerificationEmail(String toEmail, String verifyLink);
    /** Phase 4 (change email). Sent to the NEW address the account is moving to, never the old
     *  one -- proving control of that address is the entire point of this link, the same role
     *  sendEmailVerificationEmail plays at registration. */
    EmailResult sendEmailChangeVerificationEmail(String toEmail, String verifyLink);
    EmailResult sendPasswordChangedEmail(String toEmail);
    /** device/ip are the best-effort RequestMetadata labels for the request that made the
     *  deactivation call -- null-safe, since this is a security notification whose value degrades
     *  gracefully rather than failing outright when either is unavailable (e.g. a test harness, or
     *  a future non-HTTP caller). Lets a user who did not deactivate their own account tell, from
     *  the email alone, that this was not them. */
    EmailResult sendAccountDeactivatedEmail(String toEmail, Instant deactivatedAt, String device, String ip);
    EmailResult sendAccountReactivatedEmail(String toEmail);
    /** Sent after the account and all its data have already been purged, not before -- deletion is
     *  synchronous (product decision), so there is nothing left to warn about in advance and no
     *  cancel window to describe. Purely informational: what happened and when. */
    EmailResult sendAccountDeletedEmail(String toEmail, Instant deletedAt);

    /** Subscription billing V3. Sent once, from {@code RazorpayWebhookDispatcher.handleActivated}
     *  after a verified webhook confirms a real charge -- never from a checkout request's own
     *  return value (same "only the webhook activates anything" rule every other part of this
     *  subsystem follows). Fires for both a first-time paid signup and an upgrade's new
     *  subscription; never for a downgrade (no new activation happens) or a renewal (design spec
     *  never asked for a renewal receipt, and inventing one is unrequested scope). Sent from
     *  {@code EmailProperties.billingFromAddress}, not the default from-address -- see
     *  {@link EmailMessage.Sender}'s own doc comment for why. */
    EmailResult sendSubscriptionActivatedEmail(String toEmail, String fullName, String planName, String billingCycle);

    /** Fires from {@code RazorpayWebhookDispatcher.handleCharged} for every successful charge --
     *  first purchase, upgrade, AND renewal alike, unlike {@link #sendSubscriptionActivatedEmail}
     *  above which deliberately skips renewals. That method is a one-time "your plan changed"
     *  notice; this is a per-charge receipt, and a receipt is owed for every payment actually
     *  taken, not just the first one. {@code invoicePdf} is the exact same document
     *  {@code InvoiceService} generates for GET /api/v1/billing/history/{id}/invoice -- one
     *  source of truth for the PDF, whether the user downloads it or it arrives by email. Sent
     *  from {@link EmailMessage.Sender#BILLING}, same as the activation email. */
    EmailResult sendInvoiceEmail(String toEmail, String fullName, String planName, EmailAttachment invoicePdf);

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
}
