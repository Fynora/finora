package com.finora.security;

import com.finora.config.JwtProperties;
import com.finora.entity.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Previously had no dedicated coverage at all. Bug 53
 * (docs/quality/bug-reports/BUG_REVIEW_REPORT.md) is the reason SameSite is now a constructor
 * parameter rather than the hardcoded "Lax" literal it used to be -- see this file's own tests for
 * that, plus a lock-in of the other security-relevant attributes (HttpOnly, Secure, host-only, the
 * scoped path) and the cookie-over-body resolve() precedence the class's own doc comment states.
 *
 * <p>Audit F-14 (2026-09-24) split the cookie per portal; the tests at the bottom pin that the
 * two names never read each other's value.
 */
class RefreshTokenCookieTest {

    private static final String USER = User.SCOPE_USER;
    private static final String ADMIN = User.SCOPE_ADMIN;

    private JwtProperties jwtProperties() {
        JwtProperties props = new JwtProperties();
        props.setRefreshExpirationMs(30L * 24 * 60 * 60 * 1000);
        return props;
    }

    private static HttpServletRequest requestWithCookies(Cookie... cookies) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(cookies.length == 0 ? null : cookies);
        return request;
    }

    @Test
    void issue_defaultsSameSiteToLax_matchingTheCurrentSameRegistrableDomainDeployment() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        ResponseCookie result = cookie.issue(USER, "a-refresh-token");

        assertThat(result.getSameSite()).isEqualTo("Lax");
    }

    /** Bug 53's actual fix: SameSite is configurable rather than hardcoded, so a deployment where
     *  the SPA and API no longer share a registrable domain can switch to None without a code
     *  change. */
    @Test
    void issue_honorsAConfiguredSameSiteValue() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "None");

        ResponseCookie result = cookie.issue(USER, "a-refresh-token");

        assertThat(result.getSameSite()).isEqualTo("None");
    }

    @Test
    void issue_setsHttpOnlyAndSecure_andNoDomainAttribute() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        ResponseCookie result = cookie.issue(USER, "a-refresh-token");

        assertThat(result.isHttpOnly()).isTrue();
        assertThat(result.isSecure()).isTrue();
        // Host-only, deliberately -- see the class's own doc comment on why no Domain attribute.
        assertThat(result.getDomain()).isNull();
        assertThat(result.getPath()).isEqualTo("/api/v1/auth");
    }

    @Test
    void clear_expiresImmediately_withEveryOtherAttributeMatchingIssue() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        ResponseCookie result = cookie.clear(USER);

        assertThat(result.getName()).isEqualTo(RefreshTokenCookie.NAME);
        assertThat(result.getMaxAge().getSeconds()).isZero();
        assertThat(result.getPath()).isEqualTo("/api/v1/auth");
        assertThat(result.getSameSite()).isEqualTo("Lax");
    }

    @Test
    void resolve_prefersTheCookieOverTheBodyToken_whenBothArePresent() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");
        HttpServletRequest request = requestWithCookies(new Cookie(RefreshTokenCookie.NAME, "from-cookie"));

        var resolved = cookie.resolve(request, "from-body", USER);

        assertThat(resolved).contains("from-cookie");
    }

    @Test
    void resolve_fallsBackToTheBodyToken_whenNoCookieIsPresent() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        var resolved = cookie.resolve(requestWithCookies(), "from-body", USER);

        assertThat(resolved).contains("from-body");
    }

    @Test
    void resolve_isEmpty_whenNeitherTransportSuppliesAToken() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        var resolved = cookie.resolve(requestWithCookies(), null, USER);

        assertThat(resolved).isEmpty();
    }

    // ---------------------------------------------------------------- one cookie per portal (F-14)

    @Test
    void portalOf_isAdminOnlyForAnExplicitAdminScope_andUserForEverythingElse() {
        assertThat(RefreshTokenCookie.portalOf("ADMIN")).isEqualTo(ADMIN);
        assertThat(RefreshTokenCookie.portalOf("admin")).isEqualTo(ADMIN);
        assertThat(RefreshTokenCookie.portalOf("USER")).isEqualTo(USER);
        assertThat(RefreshTokenCookie.portalOf(null)).isEqualTo(USER);
        assertThat(RefreshTokenCookie.portalOf("anything-else")).isEqualTo(USER);
    }

    @Test
    void theTwoPortalsWriteDifferentCookieNames_withTheUserNameUnchanged() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        assertThat(cookie.issue(USER, "t").getName()).isEqualTo("finora_refresh_token");
        assertThat(cookie.issue(ADMIN, "t").getName()).isEqualTo("finora_admin_refresh_token");
        assertThat(cookie.clear(ADMIN).getName()).isEqualTo(RefreshTokenCookie.ADMIN_NAME);
        assertThat(cookie.clear(ADMIN).getMaxAge().getSeconds()).isZero();
    }

    @Test
    void anAdminPortalRequestNeverReadsTheUserCookie_andFallsToTheBodyInstead() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");
        HttpServletRequest request = requestWithCookies(new Cookie(RefreshTokenCookie.NAME, "user-token"));

        assertThat(cookie.fromCookie(request, ADMIN)).isEmpty();
        assertThat(cookie.resolve(request, null, ADMIN)).isEmpty();
        assertThat(cookie.resolve(request, "from-body", ADMIN)).contains("from-body");
    }

    @Test
    void eachPortalReadsItsOwnCookieWhenBothArePresent() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");
        HttpServletRequest request = requestWithCookies(
                new Cookie(RefreshTokenCookie.NAME, "user-token"),
                new Cookie(RefreshTokenCookie.ADMIN_NAME, "admin-token"));

        assertThat(cookie.fromCookie(request, USER)).contains("user-token");
        assertThat(cookie.fromCookie(request, ADMIN)).contains("admin-token");
    }
}
