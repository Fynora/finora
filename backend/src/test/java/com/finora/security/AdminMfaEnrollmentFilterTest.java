package com.finora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.service.AdminMfaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA 3.3.1: MFA is mandatory for every admin-scope account. These pin the behaviour of the gate
 * that makes it so: who is stopped, where they are still let through (they must be able to enrol,
 * sign out and clear the phone gate, or "enforced" would mean "locked out"), and that it fails
 * closed.
 */
class AdminMfaEnrollmentFilterTest {

    private AdminMfaService adminMfaService;
    private AdminMfaEnrollmentFilter filter;
    private FilterChain filterChain;
    private HttpServletResponse response;
    private StringWriter responseBody;

    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        adminMfaService = mock(AdminMfaService.class);
        when(adminMfaService.isEnforced()).thenReturn(true);
        filter = new AdminMfaEnrollmentFilter(adminMfaService, new ObjectMapper().registerModule(new JavaTimeModule()));
        filterChain = mock(FilterChain.class);
        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getServletPath()).thenReturn(path);
        when(request.getContextPath()).thenReturn("");
        // Same reason as PhoneVerificationFilterTest.requestFor: PathPatternRequestMatcher reads the
        // servlet mapping, which a real container populates and a bare mock does not.
        when(request.getHttpServletMapping()).thenReturn(mock(HttpServletMapping.class));
        return request;
    }

    private void authenticate(String username, String... authorities) {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername(username).password("irrelevant").authorities(authorities).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void authenticateAdmin() {
        authenticate(adminId.toString(), "PORTAL_ADMIN", "USER_VIEW");
    }

    private void assertBlocked(String method, String path) throws Exception {
        filter.doFilter(request(method, path), response, filterChain);
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(responseBody.toString()).contains("MFA_ENROLLMENT_REQUIRED");
    }

    private void assertPassesThrough(String method, String path) throws Exception {
        HttpServletRequest request = request(method, path);
        filter.doFilter(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void blocksAnUnenrolledAdmin_fromAnAdminEndpoint() throws Exception {
        authenticateAdmin();
        when(adminMfaService.isEnrolled(adminId)).thenReturn(false);

        assertBlocked("GET", "/api/v1/admin/users");
    }

    @Test
    void blocksAnUnenrolledAdmin_onEveryMethod() throws Exception {
        authenticateAdmin();
        when(adminMfaService.isEnrolled(adminId)).thenReturn(false);

        assertBlocked("POST", "/api/v1/admin/merchant-templates");
    }

    @Test
    void letsAnEnrolledAdminThrough() throws Exception {
        authenticateAdmin();
        when(adminMfaService.isEnrolled(adminId)).thenReturn(true);

        assertPassesThrough("GET", "/api/v1/admin/users");
    }

    @Test
    void doesNothingWhileEnforcementIsOff() throws Exception {
        when(adminMfaService.isEnforced()).thenReturn(false);
        authenticateAdmin();
        when(adminMfaService.isEnrolled(adminId)).thenReturn(false);

        assertPassesThrough("GET", "/api/v1/admin/users");
    }

    @Test
    void neverGatesAnOrdinaryUserAccount() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId.toString(), "PORTAL_USER");
        when(adminMfaService.isEnrolled(userId)).thenReturn(false);

        assertPassesThrough("GET", "/api/v1/accounts");
    }

    @Test
    void leavesAnUnauthenticatedRequestToSecurityConfig() throws Exception {
        assertPassesThrough("GET", "/api/v1/admin/users");
    }

    @Test
    void failsClosed_whenAnAdminsPrincipalIsNotAUserId() throws Exception {
        authenticate("not-a-uuid", "PORTAL_ADMIN");

        assertBlocked("GET", "/api/v1/admin/users");
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/v1/admin-mfa/status",
            "POST,/api/v1/admin-mfa/enroll",
            "POST,/api/v1/admin-mfa/confirm",
            "POST,/api/v1/admin-mfa/disable",
            "POST,/api/v1/auth/logout",
            "POST,/api/v1/auth/refresh",
            "POST,/api/v1/phone/send-otp",
            "POST,/api/v1/users/me/phone-change/start",
            "GET,/api/v1/users/me",
            "GET,/api/v1/setup/status",
            "POST,/api/v1/setup/complete"
    })
    void anUnenrolledAdminCanStillReachWhatItNeedsToEnrolSignOutAndVerifyItsPhone(String method, String path) throws Exception {
        authenticateAdmin();
        when(adminMfaService.isEnrolled(adminId)).thenReturn(false);

        assertPassesThrough(method, path);
    }

    @ParameterizedTest
    @CsvSource({
            // The allow-list is exact where it has to be. Each of these sits next to an allowed path
            // and must stay blocked, because an unenrolled admin has no business there.
            "PUT,/api/v1/users/me",
            "GET,/api/v1/users/me/access",
            "PUT,/api/v1/users/me/password",
            "GET,/api/v1/setup/complete",
            "POST,/api/v1/setup/status",
            "GET,/api/v1/admin-mfa-other",
            "GET,/api/v1/admin/users",
            "GET,/api/v1/admin/audit-logs"
    })
    void anUnenrolledAdminIsStillBlockedNextToTheAllowedPaths(String method, String path) throws Exception {
        authenticateAdmin();
        when(adminMfaService.isEnrolled(adminId)).thenReturn(false);

        assertBlocked(method, path);
    }
}
