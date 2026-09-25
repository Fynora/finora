package com.finora.integrations.google;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization URL, built for real rather than mocked (the other tests replace
 * {@link GoogleOAuthClient#buildAuthorizationUrl} with a stub, so nothing else looks at what it
 * produces).
 *
 * <p>Google's verification team told us to send {@code include_granted_scopes=false}: with
 * {@code true} the grant also carries scopes the user granted earlier, including ones since removed
 * from the consent screen, which makes the grant look unverified.
 */
class GoogleOAuthClientAuthorizationUrlTest {

    private GoogleOAuthClient clientRequesting(List<String> scopes) {
        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setClientId("test-client-id");
        properties.setRedirectUri("https://api.example.test/callback");
        properties.setScopes(scopes);
        properties.setAuthorizationEndpoint("https://accounts.example.test/authorize");
        return new GoogleOAuthClient(properties);
    }

    @Test
    @DisplayName("asks Google not to fold in scopes granted earlier")
    void doesNotIncludeGrantedScopes() {
        String url = clientRequesting(List.of("openid")).buildAuthorizationUrl("state-1");

        assertThat(url).contains("&include_granted_scopes=false");
        assertThat(url).doesNotContain("include_granted_scopes=true");
    }

    @Test
    @DisplayName("still asks for offline access with a fresh consent, and carries the state and scopes")
    void keepsTheRestOfTheRequest() {
        String url = clientRequesting(List.of("openid", "https://www.googleapis.com/auth/userinfo.email"))
                .buildAuthorizationUrl("state-2");

        URI uri = URI.create(url);
        assertThat(uri.getScheme() + "://" + uri.getHost() + uri.getPath())
                .isEqualTo("https://accounts.example.test/authorize");
        assertThat(uri.getRawQuery())
                .contains("client_id=test-client-id")
                .contains("response_type=code")
                .contains("access_type=offline")
                .contains("prompt=consent")
                .contains("state=state-2")
                .contains("scope=openid+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.email");
    }
}
