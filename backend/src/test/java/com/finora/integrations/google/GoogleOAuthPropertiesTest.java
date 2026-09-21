package com.finora.integrations.google;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pause switch ({@code GMAIL_SYNC_ENABLED}) is deliberately separate from whether the Google
 * client is configured: "we chose to pause this" and "the client secret is missing" are different
 * situations, and pausing must not require deleting the credentials.
 */
class GoogleOAuthPropertiesTest {

    private static GoogleOAuthProperties configured() {
        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setClientId("client");
        properties.setClientSecret("secret");
        properties.setRedirectUri("https://api.example.test/callback");
        return properties;
    }

    @Test
    @DisplayName("the feature is switched on by default, so nothing changes until the variable is set")
    void enabledByDefault() {
        assertThat(new GoogleOAuthProperties().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("available only when switched on AND configured")
    void availableNeedsBoth() {
        GoogleOAuthProperties on = configured();
        assertThat(on.isAvailable()).isTrue();

        GoogleOAuthProperties paused = configured();
        paused.setEnabled(false);
        assertThat(paused.isAvailable()).isFalse();

        GoogleOAuthProperties unconfigured = new GoogleOAuthProperties();
        assertThat(unconfigured.isAvailable()).isFalse();

        GoogleOAuthProperties pausedAndUnconfigured = new GoogleOAuthProperties();
        pausedAndUnconfigured.setEnabled(false);
        assertThat(pausedAndUnconfigured.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("pausing does not touch the credentials: it is still 'configured', ready to switch back on")
    void pausingKeepsTheCredentials() {
        GoogleOAuthProperties paused = configured();
        paused.setEnabled(false);

        assertThat(paused.isConfigured()).isTrue();
        assertThat(paused.getClientId()).isEqualTo("client");
        assertThat(paused.getClientSecret()).isEqualTo("secret");

        paused.setEnabled(true);
        assertThat(paused.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("any one missing credential still makes the feature unavailable, switched on or not")
    void eachMissingCredentialMakesItUnavailable() {
        GoogleOAuthProperties noId = configured();
        noId.setClientId(" ");
        GoogleOAuthProperties noSecret = configured();
        noSecret.setClientSecret(null);
        GoogleOAuthProperties noRedirect = configured();
        noRedirect.setRedirectUri("");

        assertThat(noId.isAvailable()).isFalse();
        assertThat(noSecret.isAvailable()).isFalse();
        assertThat(noRedirect.isAvailable()).isFalse();
    }
}
