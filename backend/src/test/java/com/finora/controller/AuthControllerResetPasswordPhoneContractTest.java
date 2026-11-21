package com.finora.controller;

import com.finora.config.JwtProperties;
import com.finora.dto.AuthDtos.VerifyResetPasswordPhoneRequest;
import com.finora.dto.AuthDtos.VerifyResetPasswordPhoneResponse;
import com.finora.exception.GlobalExceptionHandler;
import com.finora.integrations.apple.login.AppleIdTokenVerifierService;
import com.finora.integrations.google.login.GoogleIdTokenVerifierService;
import com.finora.security.JwtService;
import com.finora.security.RefreshTokenCookie;
import com.finora.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The request and response shape of {@code POST /api/v1/auth/reset-password/phone}, as a client
 * sees it over HTTP.
 *
 * <p>BH-015 turned this route around: it used to take the reset link's token and hand back the
 * account's phone number, and now takes the token together with the number the person typed and
 * hands back only a message. The admin portal went on calling it the old way, sending the token
 * alone and reading a number out of the reply, so no admin could reset a password. Nothing failed
 * in any test, because the service tests build the request record directly and never go through
 * the validation a real request meets. These tests pin the shape at the HTTP boundary, so a client
 * written against the old shape has something to fail against.
 */
class AuthControllerResetPasswordPhoneContractTest {

    private static final String ROUTE = "/api/v1/auth/reset-password/phone";

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        RefreshTokenCookie refreshTokenCookie = new RefreshTokenCookie(new JwtProperties(), "Lax");
        AuthController controller = new AuthController(authService, refreshTokenCookie, mock(JwtService.class),
                mock(GoogleIdTokenVerifierService.class), mock(AppleIdTokenVerifierService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(Environment.class), refreshTokenCookie))
                .build();
    }

    /** What the admin portal used to send. */
    @Test
    void aRequestWithOnlyTheToken_isRefusedBeforeItReachesTheService() throws Exception {
        mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token-abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(authService);
    }

    @Test
    void aRequestWithABlankPhoneNumber_isRefusedTheSameWay() throws Exception {
        mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token-abc\",\"phoneNumber\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(authService);
    }

    @Test
    void aRequestWithTheTokenAndTheTypedNumber_isAnsweredWithAMessageAndNoPhoneNumber() throws Exception {
        when(authService.verifyResetPasswordPhone(any()))
                .thenReturn(new VerifyResetPasswordPhoneResponse("Phone number confirmed."));

        mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"reset-token-abc\",\"phoneNumber\":\"+919999999999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Phone number confirmed."))
                .andExpect(jsonPath("$.data.phoneNumber").doesNotExist());

        verify(authService).verifyResetPasswordPhone(
                new VerifyResetPasswordPhoneRequest("reset-token-abc", "+919999999999"));
    }
}
