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
     *  toward a referral. Sandbox purchases cost nothing, and RevenueCat can send them to the same
     *  webhook as production ones, so production must leave this false or anyone with a test build
     *  could earn referral months for free. Only for a test environment that exercises referrals
     *  with sandbox purchases. */
    private boolean countSandboxReferrals = false;

    public boolean isConfigured() {
        return webhookSigningSecret != null && !webhookSigningSecret.isBlank();
    }

    public String getWebhookSigningSecret() { return webhookSigningSecret; }
    public void setWebhookSigningSecret(String webhookSigningSecret) { this.webhookSigningSecret = webhookSigningSecret; }
    public boolean isCountSandboxReferrals() { return countSandboxReferrals; }
    public void setCountSandboxReferrals(boolean countSandboxReferrals) { this.countSandboxReferrals = countSandboxReferrals; }
}
