package com.finora.integrations.google;

import com.finora.integrations.google.merchant.GmailReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The callback's redirect target -- found via a live reproduction where a real Gmail connection
 * succeeded (confirmed by the backend's own log) but the browser still landed somewhere that read
 * as "signed out". Root cause: {@link GoogleOAuthProperties#getPostConnectRedirect()}'s default
 * pointed at {@code /settings}, a path the frontend router has never served -- the real route is
 * {@code /app/settings} (App.tsx's route table), and the router's catch-all sends anything else to
 * the public marketing homepage, which shows a logged-out nav regardless of session state.
 */
class GoogleOAuthControllerTest {

    private GoogleOAuthController controller() {
        return new GoogleOAuthController(
                mock(GmailConnectionService.class),
                new GoogleOAuthProperties(), // real instance -- the default value is the point
                mock(com.finora.security.CurrentUser.class),
                mock(GmailReviewService.class),
                mock(GmailManualSyncService.class));
    }

    @Test
    @DisplayName("the default post-connect redirect targets the real frontend route, /app/settings")
    void defaultPostConnectRedirectTargetsTheRealRoute() {
        assertThat(new GoogleOAuthProperties().getPostConnectRedirect())
                .isEqualTo("https://app.fynora.net/app/settings");
    }

    @Test
    @DisplayName("an invalid callback redirects to the real /app/settings route, not the bare /settings 404")
    void invalidCallbackRedirectsToTheRealSettingsRoute() {
        ResponseEntity<Void> response = controller().callback(null, null, null);

        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString())
                .startsWith("https://app.fynora.net/app/settings")
                .contains("gmail=invalid");
    }

    @Test
    @DisplayName("a declined consent redirects to the real /app/settings route")
    void declinedConsentRedirectsToTheRealSettingsRoute() {
        ResponseEntity<Void> response = controller().callback(null, null, "access_denied");

        assertThat(response.getHeaders().getLocation().toString())
                .startsWith("https://app.fynora.net/app/settings")
                .contains("gmail=declined");
    }

    @Test
    @DisplayName("the default post-connect redirect for mobile is the app's own custom scheme")
    void defaultPostConnectRedirectMobileTargetsTheAppScheme() {
        assertThat(new GoogleOAuthProperties().getPostConnectRedirectMobile())
                .isEqualTo("finora://gmail-callback");
    }

    /**
     * Mobile Gmail Sync. peekReturnPlatform is what decides this -- these tests stub it directly
     * rather than going through a real GmailConnectionService, matching this file's own posture of
     * testing the controller's redirect-selection logic in isolation (GmailConnectionServiceTest
     * covers peekReturnPlatform's own resolution rules).
     */
    @Test
    @DisplayName("a successful mobile-initiated connect redirects to the app's custom scheme, not the web URL")
    void successfulMobileConnectRedirectsToTheAppScheme() {
        GmailConnectionService service = mock(GmailConnectionService.class);
        org.mockito.Mockito.when(service.peekReturnPlatform("mobile-state")).thenReturn(ReturnPlatform.MOBILE);
        GoogleOAuthController controller = new GoogleOAuthController(
                service, new GoogleOAuthProperties(), mock(com.finora.security.CurrentUser.class),
                mock(GmailReviewService.class), mock(GmailManualSyncService.class));

        ResponseEntity<Void> response = controller.callback("a-code", "mobile-state", null);

        assertThat(response.getHeaders().getLocation().toString())
                .startsWith("finora://gmail-callback")
                .contains("gmail=connected");
    }

    @Test
    @DisplayName("a declined mobile-initiated consent also redirects to the app's custom scheme")
    void declinedMobileConsentRedirectsToTheAppScheme() {
        GmailConnectionService service = mock(GmailConnectionService.class);
        org.mockito.Mockito.when(service.peekReturnPlatform("mobile-state")).thenReturn(ReturnPlatform.MOBILE);
        GoogleOAuthController controller = new GoogleOAuthController(
                service, new GoogleOAuthProperties(), mock(com.finora.security.CurrentUser.class),
                mock(GmailReviewService.class), mock(GmailManualSyncService.class));

        ResponseEntity<Void> response = controller.callback(null, "mobile-state", "access_denied");

        assertThat(response.getHeaders().getLocation().toString())
                .startsWith("finora://gmail-callback")
                .contains("gmail=declined");
    }

    @Test
    @DisplayName("a mobile flow that fails after the state was consumed still redirects to the app's custom scheme")
    void failedMobileConnectRedirectsToTheAppScheme() {
        GmailConnectionService service = mock(GmailConnectionService.class);
        org.mockito.Mockito.when(service.peekReturnPlatform("mobile-state")).thenReturn(ReturnPlatform.MOBILE);
        org.mockito.Mockito.when(service.completeConnect("mobile-state", "a-code"))
                .thenThrow(new RuntimeException("Google token exchange failed"));
        GoogleOAuthController controller = new GoogleOAuthController(
                service, new GoogleOAuthProperties(), mock(com.finora.security.CurrentUser.class),
                mock(GmailReviewService.class), mock(GmailManualSyncService.class));

        ResponseEntity<Void> response = controller.callback("a-code", "mobile-state", null);

        assertThat(response.getHeaders().getLocation().toString())
                .startsWith("finora://gmail-callback")
                .contains("gmail=failed");
    }
}
