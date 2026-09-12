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
