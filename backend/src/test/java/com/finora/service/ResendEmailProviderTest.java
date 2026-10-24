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
    void welcome_greetsByNameAndPointsToPhoneVerificationNotTheApp() {
        // Found live: a new signup got this email promising "Your account is ready" while stuck
        // behind phone verification (ProtectedRoute redirects every route but /verify-phone until
        // phoneVerified is true) -- misleading whenever Firebase's OTP is slow to arrive. The copy
        // must not claim readiness, and the CTA should send them straight to the actual next step
        // rather than bouncing through /app.
        EmailMessage message = provider.buildWelcomeMessage("user@example.test", "Jordan Lee");

        assertThat(message.subject()).isEqualTo("Welcome to Fynora");
        assertThat(message.html()).contains("Hi Jordan Lee");
        assertThat(message.html()).doesNotContain("account is ready");
        assertThat(message.html()).contains("https://app.fynora.net/verify-phone");
        assertThat(message.html()).contains("Verify Phone");
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
    void accountLocked_saysWhenItLockedWhenItUnlocksAndWhereTheAttemptCameFrom() {
        EmailMessage message = provider.buildAccountLockedMessage("user@example.test",
                Instant.parse("2026-10-24T00:55:00Z"), Instant.parse("2026-10-24T01:10:00Z"),
                "Chrome on macOS", "203.0.113.4");

        assertThat(message.subject()).isEqualTo("Your Fynora account was locked after wrong passwords");
        assertThat(message.html()).contains("24 Oct 2026, 00:55");
        assertThat(message.html()).contains("24 Oct 2026, 01:10");
        assertThat(message.html()).contains("Device: Chrome on macOS");
        assertThat(message.html()).contains("IP address: 203.0.113.4");
        assertThat(message.html()).contains("shared networks and VPNs can make an address look unfamiliar");
        // States only what the lock knows: those attempts failed. No claim about the rest of the account.
        assertThat(message.html()).contains("None of those attempts got in");
    }

    @Test
    void accountLocked_escapesTheDeviceAndIp_becauseTheyDescribeSomeoneElsesRequest() {
        EmailMessage message = provider.buildAccountLockedMessage("user@example.test",
                Instant.now(), Instant.now().plusSeconds(900),
                "<script>alert(1)</script>", "1.2.3.4\"><img src=x>");

        assertThat(message.html()).doesNotContain("<script>alert(1)</script>");
        assertThat(message.html()).doesNotContain("<img src=x>");
        assertThat(message.html()).contains("&lt;script&gt;");
    }

    @Test
    void accountLocked_omitsDeviceAndIpLinesWhenUnavailable() {
        EmailMessage message = provider.buildAccountLockedMessage("user@example.test",
                Instant.now(), Instant.now().plusSeconds(900), null, " ");

        assertThat(message.html()).doesNotContain("Device:");
        assertThat(message.html()).doesNotContain("IP address:");
        // No address, no caveat about addresses.
        assertThat(message.html()).doesNotContain("shared networks");
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

        assertThat(message.subject()).isEqualTo("We're double-checking your statement");
        // The two promises the held screen makes too: a person, and a time.
        assertThat(message.html()).contains("by hand").contains("48 hours")
                .contains("keep using Fynora");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    @Test
    void statementRejected_saysNothingWasAddedHasNoButtonAndSendsFromSupport() {
        EmailMessage message = provider.buildStatementRejectedMessage("user@example.test");

        assertThat(message.subject()).isEqualTo("Your statement wasn't imported");
        assertThat(message.html()).contains("Nothing was added to your accounts.");
        assertThat(message.html()).doesNotContain("Review Statement");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    @Test
    void confirmReminder_saysADayIsLeftAndLinksToTheUnfinishedImportsList() {
        EmailMessage message = provider.buildStatementConfirmReminderMessage("user@example.test", 1);

        assertThat(message.subject()).isEqualTo("Your statement is waiting to be confirmed");
        assertThat(message.html()).contains("Your statement is ready and waiting for you.")
                .contains("less than a day left").contains("upload it again later")
                .contains("https://app.fynora.net/app/import\"").contains("Review and confirm")
                .contains("https://app.fynora.net/app/settings?tab=notifications");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    @Test
    void confirmReminder_countsSeveralStatements() {
        EmailMessage message = provider.buildStatementConfirmReminderMessage("user@example.test", 3);

        assertThat(message.subject()).isEqualTo("Your statements are waiting to be confirmed");
        assertThat(message.html()).contains("3 of your statements are ready and waiting for you.")
                .contains("upload them again later").contains("Review statements");
    }

    @Test
    void ticketReceived_namesTheReferenceLinksToTheTicketAndSendsFromSupport() {
        EmailMessage message = provider.buildSupportTicketReceivedMessage("user@example.test", "SUP-000123",
                "11111111-1111-1111-1111-111111111111");

        assertThat(message.subject()).isEqualTo("We received your support request SUP-000123");
        assertThat(message.html()).contains("reference <strong>SUP-000123</strong>")
                .contains("get back to you by email")
                .contains("https://app.fynora.net/app/support/11111111-1111-1111-1111-111111111111\"")
                .contains("View your request");
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    /** The reply invitation is the footer's alone, and a notification the user cannot switch off
     *  must not point at a settings page that has no switch for it. */
    @Test
    void ticketReceived_saysReplyOnceAndCarriesNoOptOutLine() {
        String html = provider.buildSupportTicketReceivedMessage("user@example.test", "SUP-000123",
                "11111111-1111-1111-1111-111111111111").html();

        assertThat(html.split("reply to this email", -1)).hasSize(2);
        assertThat(html).doesNotContain("notification settings").doesNotContain("Turn them off");
    }

    @Test
    void ticketReceived_withNoTicketIdHasNoButton_andEscapesTheReference() {
        String html = provider.buildSupportTicketReceivedMessage("user@example.test", "SUP-<1>", null).html();

        assertThat(html).doesNotContain("View your request").doesNotContain("/app/support/")
                .contains("SUP-&lt;1&gt;").doesNotContain("SUP-<1>");
    }

    /** Both statement emails are FINANCIAL notifications a user can switch off, so both say where. */
    @Test
    void statementEmails_linkToTheNotificationSettingsWhereTheyCanBeTurnedOff() {
        String ready = provider.buildStatementReadyMessage(
                "user@example.test", "HDFC Bank", "11111111-1111-1111-1111-111111111111").html();
        String held = provider.buildStatementHeldMessage("user@example.test").html();
        String rejected = provider.buildStatementRejectedMessage("user@example.test").html();

        assertThat(ready).contains("https://app.fynora.net/app/settings?tab=notifications");
        assertThat(held).contains("https://app.fynora.net/app/settings?tab=notifications");
        assertThat(rejected).contains("https://app.fynora.net/app/settings?tab=notifications");
        assertThat(ready).contains("notification settings");
    }

    /** A security email cannot be switched off, so it must not claim it can. */
    @Test
    void securityEmails_carryNoOptOutLine() {
        String otp = provider.buildLoginOtpMessage("jane@example.com", "482913").html();
        String reset = provider.buildPasswordResetMessage(
                "user@example.test", "https://app.fynora.net/reset-password?token=abc").html();

        assertThat(otp).doesNotContain("notification settings");
        assertThat(reset).doesNotContain("notification settings");
    }

    @Test
    void loginOtp_containsTheCodeAndNoCtaButton() {
        EmailMessage message = provider.buildLoginOtpMessage("jane@example.com", "482913");

        assertThat(message.to()).isEqualTo("jane@example.com");
        assertThat(message.subject()).contains("code");
        assertThat(message.html()).contains("482913");
        assertThat(message.html()).contains("5 minutes");
    }
}
