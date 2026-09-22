package com.finora.controller;

import com.finora.dto.AuthDtos.*;
import com.finora.integrations.apple.login.AppleIdTokenVerifierService;
import com.finora.integrations.google.login.GoogleIdTokenVerifierService;
import com.finora.config.JwtProperties;
import com.finora.security.RefreshTokenCookie;
import com.finora.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerOtpTest {

    private AuthController controller(AuthService authService) {
        return new AuthController(authService, new RefreshTokenCookie(new JwtProperties(), "Lax"),
                mock(GoogleIdTokenVerifierService.class), mock(AppleIdTokenVerifierService.class));
    }

    private AuthResponse sampleAuthResponse() {
        return new AuthResponse("access-token", "refresh-token", "jane@example.com", "Jane",
                true, null, UUID.randomUUID(), true);
    }

    @Test
    void otpEmailRequest_delegatesToAuthService() {
        AuthService authService = mock(AuthService.class);
        when(authService.requestEmailLoginOtp(any())).thenReturn(new EmailOtpRequestResponse("sent", null));

        var response = controller(authService).otpEmailRequest(new EmailOtpRequestRequest("jane@example.com", null));

        assertThat(response.getBody().data().message()).isEqualTo("sent");
    }

    @Test
    void otpEmailLogin_setsTheRefreshCookieOnSuccess() {
        AuthService authService = mock(AuthService.class);
        when(authService.loginWithEmailOtp(any())).thenReturn(sampleAuthResponse());

        var response = controller(authService).otpEmailLogin(new EmailOtpLoginRequest("jane@example.com", "482913", null));

        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotNull();
        assertThat(response.getBody().data().email()).isEqualTo("jane@example.com");
    }

    @Test
    void otpPhoneLogin_setsTheRefreshCookieOnSuccess() {
        AuthService authService = mock(AuthService.class);
        when(authService.loginWithPhoneOtp(any())).thenReturn(sampleAuthResponse());

        var response = controller(authService).otpPhoneLogin(new PhoneOtpLoginRequest("valid-firebase-token", null));

        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotNull();
    }

    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }
}
