package com.finora.security;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which requests a suspended account may make (privacy workstream 10). */
class SuspendedAccountInterceptorTest {

    private final SuspendedAccountInterceptor interceptor = new SuspendedAccountInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String... authorities) {
        var granted = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", null, granted));
    }

    private boolean allows(String method, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/ignored");
        if (pattern != null) request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void anActiveAccount_isNeverStopped() {
        signIn("PORTAL_USER");
        assertThat(allows("GET", "/api/v1/accounts")).isTrue();
        assertThat(allows("DELETE", "/api/v1/transactions/{id}")).isTrue();
    }

    @Test
    void anAnonymousRequest_isNotThisInterceptorsToJudge() {
        assertThat(allows("GET", "/api/v1/accounts")).isTrue();
    }

    @Test
    void aSuspendedAccount_reachesEveryAllowedRoute() {
        signIn("PORTAL_USER", SuspendedAccountInterceptor.SUSPENDED_MARKER);
        for (String allowed : SuspendedAccountInterceptor.ALLOWED) {
            String[] parts = allowed.split(" ", 2);
            assertThat(allows(parts[0], parts[1])).as(allowed).isTrue();
        }
    }

    @Test
    void aSuspendedAccount_isRefusedEverywhereElse_withTheSuspendedCode() {
        signIn("PORTAL_USER", SuspendedAccountInterceptor.SUSPENDED_MARKER);
        for (String refused : List.of(
                "GET /api/v1/accounts",
                "GET /api/v1/users/me",
                "POST /api/v1/users/me/account/delete",
                "POST /api/v1/users/me/password-change/start",
                "POST /api/v1/support/tickets",
                // the right path with the wrong method is still refused
                "POST /api/v1/billing/subscription",
                "GET /api/v1/users/me/data-export")) {
            String[] parts = refused.split(" ", 2);
            assertThatThrownBy(() -> allows(parts[0], parts[1])).as(refused)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.AUTH_ACCOUNT_SUSPENDED));
        }
    }

    /** No matched handler (a 404 on its way) is not a request to judge. */
    @Test
    void aRequestThatMatchedNoHandler_isLetThrough() {
        signIn(SuspendedAccountInterceptor.SUSPENDED_MARKER);
        assertThat(allows("GET", null)).isTrue();
    }
}
