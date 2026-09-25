package com.finora.security;

import com.finora.config.JwtProperties;
import com.finora.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The refresh token as an {@code HttpOnly} cookie, and the one place that decides which transport
 * a request used.
 *
 * <h2>Why a cookie at all</h2>
 * The refresh token is the durable credential — good for up to the absolute session cap, where an
 * access token is good for fifteen minutes. In {@code localStorage} it is readable by any script
 * that manages to run on the page; as {@code HttpOnly} it is not readable by script at all, which
 * is the single largest reduction in blast radius available for an XSS on a page that shows bank
 * statements.
 *
 * <h2>One cookie per portal</h2>
 * Audit F-14 (2026-09-24). The user app and the admin portal talk to the same API host with
 * credentials, and a cookie is keyed by host and name. With one name, signing in to the admin
 * portal overwrote the cookie the user app relied on and vice versa; the user app's next silent
 * refresh then presented the admin's token and came back holding an {@code ADMIN}-scope access
 * token, so the user SPA quietly operated as the admin account and audit rows attributed its
 * actions to the admin identity. Now a {@code USER}-scope session lives in {@link #NAME} and an
 * {@code ADMIN}-scope one in {@link #ADMIN_NAME}. The portal is chosen from the account's scope
 * when the cookie is written, and from the request's {@code scope} hint when it is read -- the
 * same field {@code LoginRequest} already carries, defaulting to {@code USER} so a client that
 * sends nothing behaves exactly as before. {@code AuthService.refresh} then checks the token it
 * was handed actually belongs to that portal, so a cookie somehow holding the other portal's
 * token is refused rather than honoured.
 *
 * <h2>Host-only, deliberately</h2>
 * No {@code Domain} attribute. Setting {@code Domain=.fynora.net} would send this credential
 * to every current and future subdomain — marketing pages, status pages, anything — when the only
 * thing that ever needs it is the API host that issued it. Omitting {@code Domain} makes the
 * cookie host-only, which is least privilege and costs nothing: the frontend never reads it, so
 * it gains nothing from being shared.
 *
 * <h2>Why {@code SameSite=Lax} is enough</h2>
 * {@code app.fynora.net} and {@code api.fynora.net} share the registrable domain
 * {@code fynora.net}, so a request from one to the other is same-SITE even though it is
 * cross-ORIGIN. Lax cookies are sent on same-site subresource requests. That matters beyond
 * tidiness: the alternative, {@code SameSite=None}, is what browsers are progressively restricting
 * as third-party cookies, and a credential that depends on it has a deprecation clock attached.
 * This only became available when the API moved onto the same registrable domain.
 *
 * <p>Bug 53 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). {@code Lax} is only correct while
 * that precondition holds -- on a deployment where the SPA and API origins have DIFFERENT
 * registrable domains, a Lax cookie is silently withheld by the browser entirely (never stored,
 * never sent), and the app falls back to the {@code localStorage} refresh token this cookie exists
 * to replace, with no error and no signal anything degraded. Configurable rather than hardcoded so
 * that regression doesn't need a code change to fix -- but the DEFAULT stays {@code Lax}, matching
 * what's actually true today per the paragraph above; this class has no way to verify live DNS
 * from inside the JVM, so changing the default without confirming the precondition no longer holds
 * would be trading a real, working control for a guess.
 *
 * <h2>Path</h2>
 * Scoped to {@code /api/v1/auth}. Every other endpoint authenticates with the access token and has
 * no use for this cookie, so there is no reason for the browser to attach it to them.
 */
@Component
public class RefreshTokenCookie {

    /** The {@code USER}-portal cookie. The original, unchanged name: every user-app session in
     *  the wild is in it, and the user app sends no scope hint. */
    public static final String NAME = "finora_refresh_token";

    /** The {@code ADMIN}-portal cookie. New with F-14: an admin already signed in when this ships
     *  holds the admin token in {@link #NAME}, which the admin portal no longer reads, so they
     *  sign in once more. The user app, if it finds that admin token in its own cookie, is refused
     *  by {@code AuthService.refresh}'s portal check and the cookie is cleared. */
    public static final String ADMIN_NAME = "finora_admin_refresh_token";

    /**
     * Request attribute naming the portal ({@code USER} or {@code ADMIN}) an auth request is
     * acting for, set by {@code AuthController} before it calls the service. Read by
     * {@code GlobalExceptionHandler}, which clears the refresh cookie on session-ending errors and
     * has to clear the right one: clearing both would end the other portal's session in the same
     * browser, which is the confusion F-14 exists to remove.
     */
    public static final String PORTAL_ATTRIBUTE = "com.finora.security.refreshCookiePortal";

    /** Only the auth endpoints exchange or clear a refresh token; nothing else needs it attached. */
    private static final String PATH = "/api/v1/auth";

    private final JwtProperties jwtProperties;
    private final String sameSite;

    public RefreshTokenCookie(JwtProperties jwtProperties,
                               @Value("${app.security.refresh-cookie-same-site:Lax}") String sameSite) {
        this.jwtProperties = jwtProperties;
        this.sameSite = sameSite;
    }

    /** Normalises a scope hint or an account scope to exactly {@code ADMIN} or {@code USER},
     *  the same rule {@code AuthService} applies to {@code LoginRequest.scope}: anything that is
     *  not {@code ADMIN} (including null) is the user portal. */
    public static String portalOf(String scope) {
        return User.SCOPE_ADMIN.equalsIgnoreCase(scope) ? User.SCOPE_ADMIN : User.SCOPE_USER;
    }

    /** The cookie name that carries the given portal's refresh token. */
    public static String nameFor(String portal) {
        return User.SCOPE_ADMIN.equals(portalOf(portal)) ? ADMIN_NAME : NAME;
    }

    /** The refresh token this request carried in the given portal's cookie, if any. The other
     *  portal's cookie is never consulted: an admin-portal request with only a user cookie has no
     *  admin credential, and must not be handed the user's. */
    public Optional<String> fromCookie(HttpServletRequest request, String portal) {
        String name = nameFor(portal);
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /**
     * The refresh token this request supplied for the given portal, preferring the cookie.
     *
     * <p>Precedence is that portal's cookie, then body, then absent. Both transports stay
     * supported permanently rather than the body being removed once web migrates: mobile is a
     * native client with no cookie jar and will always send a body, and integration tests are
     * easier to write against one. Branching the endpoint instead would duplicate rotation, reuse
     * detection and the session limits across two paths that must never disagree.
     *
     * <p>Cookie wins when both are present because it is the transport the browser cannot be
     * tricked into forging by script. In practice they carry the same value: every path that
     * issues a token writes both.
     */
    public Optional<String> resolve(HttpServletRequest request, String bodyToken, String portal) {
        Optional<String> fromCookie = fromCookie(request, portal);
        if (fromCookie.isPresent()) {
            return fromCookie;
        }
        return Optional.ofNullable(bodyToken).filter(t -> !t.isBlank());
    }

    /** Set-Cookie carrying a freshly issued or rotated refresh token, in the given portal's cookie. */
    public ResponseCookie issue(String portal, String rawToken) {
        return base(nameFor(portal), rawToken)
                .maxAge(Duration.ofMillis(jwtProperties.getRefreshExpirationMs()))
                .build();
    }

    /**
     * Set-Cookie that removes the given portal's cookie. Every attribute except {@code Max-Age}
     * must match the original or the browser treats it as a different cookie and quietly keeps
     * the old one — a logout that appears to work and leaves the credential in place.
     */
    public ResponseCookie clear(String portal) {
        return base(nameFor(portal), "").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite(sameSite)
                .path(PATH);
    }
}
