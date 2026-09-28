package com.finora.integrations.revenuecat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Subscription billing V4. Same "unconfigured is a supported state" posture as RazorpayProperties
 *  -- a missing RevenueCat credential disables mobile IAP, nothing else. */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.revenuecat")
public class RevenueCatProperties {

    private String webhookSigningSecret;

    /** Whether a SANDBOX purchase (App Store sandbox / TestFlight, Play test tracks) may count
     *  toward a referral. Sandbox purchases cost nothing and reach this webhook, so production
     *  must leave this false, or anyone with a test build could earn referral months for free.
     *  Only for a test environment that exercises referrals with sandbox purchases.
     *
     *  <p>Sandbox purchases still unlock the plan itself, deliberately: Apple's App Review buys
     *  with sandbox accounts against the production server (Apple TN2413: a production-signed
     *  app "connects to your production servers, but ... to the test environment for the App
     *  Store"), so ignoring sandbox events would leave the reviewer's purchase unlocking nothing.
     *  Sandbox subscriptions renew on an accelerated clock and stop after a few renewals, so
     *  EXPIRATION ends that access on its own. */
    private boolean countSandboxReferrals = false;

    public boolean isConfigured() {
        return webhookSigningSecret != null && !webhookSigningSecret.isBlank();
    }

    public String getWebhookSigningSecret() { return webhookSigningSecret; }
    public void setWebhookSigningSecret(String webhookSigningSecret) { this.webhookSigningSecret = webhookSigningSecret; }
    public boolean isCountSandboxReferrals() { return countSandboxReferrals; }
    public void setCountSandboxReferrals(boolean countSandboxReferrals) { this.countSandboxReferrals = countSandboxReferrals; }
}
