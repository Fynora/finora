package com.finora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminMfaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes two-factor authentication mandatory for every admin-scope account (CASA 3.3.1: "multi-
 * factor authentication shall be enforced for all administrative accounts").
 *
 * <p>Enrolment used to be opt-in: {@code AuthService.login()} asks for a second factor only from an
 * admin who has enrolled, so an admin who never did signed in with a password alone. This filter
 * closes that. With {@code app.admin-mfa.enforced} on, an authenticated admin-scope request from an
 * account with no finished enrolment is refused with {@link #ERROR_CODE} everywhere except the
 * endpoints needed to finish enrolling (and to sign out, refresh, or verify the phone), which the
 * admin portal turns into a forced "set up two-factor authentication" screen. Nobody is locked out:
 * the way forward is always to enrol, and the enrolment endpoints stay reachable.
 *
 * <p>Enforced server-side, per request, for the same reason {@link PhoneVerificationFilter} is:
 * gating only the login screen would leave a session that already exists (or a direct API call)
 * untouched, and a redirect in the browser is not a control. A session created before enrolment is
 * therefore gated on its next request, not left running.
 *
 * <p>Runs after {@link PhoneVerificationFilter}, so the two gates apply in a fixed order (phone
 * first, then this) and the allow-list below is a superset of that filter's: an admin who is
 * unverified AND unenrolled must still be able to reach the phone-verification endpoints, or the
 * phone gate could never be cleared.
 *
 * <p>Admin scope is read from the authority set {@code AuthorizationService} already computed for
 * the request ({@code PORTAL_ADMIN}), so identifying an admin costs no query; enrolment costs one
 * indexed lookup, and only for admin requests while enforcement is on.
 *
 * <p>Fails closed: a principal that is an admin but whose id cannot be read is treated as not
 * enrolled, never as "let it through" (the failure mode {@code PhoneVerificationFilter}'s Bug 26
 * fixed for the same shape of gate).
 *
 * <p>Paths are matched with {@link PathPatternRequestMatcher}, never by comparing the raw URI, per
 * {@code FilterPathMatchingTest}: the router matches the decoded path, so a string comparison here
 * could be bypassed by percent-encoding one character.
 */
@Component
public class AdminMfaEnrollmentFilter extends OncePerRequestFilter {

    /** Wire value the admin portal keys off (see admin-portal api/errorCodes.ts). A plain string
     *  like {@code PHONE_VERIFICATION_REQUIRED}, not an {@code ErrorCode} enum entry: it is written
     *  from a filter, which has no exception path through {@code GlobalExceptionHandler}. */
    public static final String ERROR_CODE = "MFA_ENROLLMENT_REQUIRED";

    static final String ADMIN_AUTHORITY = "PORTAL_ADMIN";

    private static final PathPatternRequestMatcher.Builder REQUEST_MATCHERS = PathPatternRequestMatcher.withDefaults();

    // The enrolment endpoints themselves (status, enroll, confirm, disable) -- the whole point.
    private static final PathPatternRequestMatcher ADMIN_MFA_ENDPOINTS = REQUEST_MATCHERS.matcher("/api/v1/admin-mfa/**");
    // login, refresh, logout, forgot/reset password. Logout in particular must stay reachable, or a
    // gated admin could not end their session (PhoneVerificationFilter's own doc describes that bug).
    private static final PathPatternRequestMatcher AUTH_ENDPOINTS = REQUEST_MATCHERS.matcher("/api/v1/auth/**");
    // The rest mirror PhoneVerificationFilter's allow-list exactly, so an admin who is both
    // unverified and unenrolled can still clear the phone gate that runs before this one.
    private static final PathPatternRequestMatcher PHONE_ENDPOINTS = REQUEST_MATCHERS.matcher("/api/v1/phone/**");
    private static final PathPatternRequestMatcher PHONE_CHANGE_ENDPOINTS =
            REQUEST_MATCHERS.matcher("/api/v1/users/me/phone-change/**");
    private static final PathPatternRequestMatcher USER_ME_ENDPOINT =
            REQUEST_MATCHERS.matcher(HttpMethod.GET, "/api/v1/users/me");
    private static final PathPatternRequestMatcher SETUP_STATUS_ENDPOINT =
            REQUEST_MATCHERS.matcher(HttpMethod.GET, "/api/v1/setup/status");
    // First-run setup. Only the one BOOTSTRAP_ADMIN account holds the SYSTEM_INITIALIZE permission
    // that guards it (SetupController), and it creates the real admin, who enrols at first login.
    // Gating it would force a throwaway bootstrap account through enrolment on a fresh install.
    private static final PathPatternRequestMatcher SETUP_COMPLETE_ENDPOINT =
            REQUEST_MATCHERS.matcher(HttpMethod.POST, "/api/v1/setup/complete");

    private final AdminMfaService adminMfaService;
    private final ObjectMapper objectMapper;

    /**
     * {@code @Lazy} breaks a real cycle: SecurityConfig needs this filter to build the filter chain,
     * and {@link AdminMfaService} reaches GoogleReauthVerifier, which needs the PasswordEncoder bean
     * that SecurityConfig itself defines. Resolved on the first request instead, when the whole
     * context exists. Found by AdminMfaEnforcementIT: the app failed to start without it.
     */
    public AdminMfaEnrollmentFilter(@Lazy AdminMfaService adminMfaService, ObjectMapper objectMapper) {
        this.adminMfaService = adminMfaService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (adminMfaService.isEnforced() && mustEnrolBefore(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(
                    "Set up two-factor authentication to continue.", ERROR_CODE)));
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** True for an authenticated admin-scope request, outside the allow-list, from an account with
     *  no finished enrolment. */
    private boolean mustEnrolBefore(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserDetails userDetails)) {
            return false; // unauthenticated: SecurityConfig's own rules answer 401
        }
        boolean admin = userDetails.getAuthorities().stream().anyMatch(a -> ADMIN_AUTHORITY.equals(a.getAuthority()));
        if (!admin || isAllowListed(request)) {
            return false;
        }
        // The principal's username is the user id (see CurrentUserDetailsService).
        Optional<UUID> id = parseId(userDetails.getUsername());
        return id.isEmpty() || !adminMfaService.isEnrolled(id.get());
    }

    private static boolean isAllowListed(HttpServletRequest request) {
        return ADMIN_MFA_ENDPOINTS.matches(request) || AUTH_ENDPOINTS.matches(request)
                || PHONE_ENDPOINTS.matches(request) || PHONE_CHANGE_ENDPOINTS.matches(request)
                || USER_ME_ENDPOINT.matches(request) || SETUP_STATUS_ENDPOINT.matches(request)
                || SETUP_COMPLETE_ENDPOINT.matches(request);
    }

    private static Optional<UUID> parseId(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
