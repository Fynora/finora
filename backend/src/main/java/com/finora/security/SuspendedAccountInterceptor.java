package com.finora.security;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Set;

/**
 * Confines a suspended account to what it may still do (privacy workstream 10): read why it was
 * suspended, appeal or ask for deletion, download its data, see whether it pays for Plus through a
 * store, and sign out. Every other endpoint answers {@link ErrorCode#AUTH_ACCOUNT_SUSPENDED}.
 *
 * <p>Applies to every token of a suspended account, not only the one sign-in hands out on the
 * refusal ({@code AuthService.refuseSuspendedSignIn}): a token issued before the suspension is
 * confined from its next request too, because {@link CurrentUserDetailsService} marks the principal
 * from the user row it loads fresh on every request.
 *
 * <p>Matched on the handler's URI template, never the raw URI, so an encoded or decorated path
 * cannot slip past it; a path that matches no handler is not a request this needs to judge.
 * Registered for {@code /api/v1/**} in {@code WebMvcConfig}.
 */
@Component
public class SuspendedAccountInterceptor implements HandlerInterceptor {

    /** The authority {@link CurrentUserDetailsService} adds for a suspended account. Not a permission:
     *  nothing grants it, and no endpoint is gated on it except through this class. */
    public static final String SUSPENDED_MARKER = "ACCOUNT_STATUS_SUSPENDED";

    /** Method and URI template of every endpoint a suspended account may call. */
    static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/users/me/suspension",
            "POST /api/v1/users/me/suspension/requests",
            "POST /api/v1/users/me/data-export",
            "GET /api/v1/billing/subscription",
            "POST /api/v1/auth/logout");

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!isSuspended(SecurityContextHolder.getContext().getAuthentication())) return true;
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) return true;
        if (ALLOWED.contains(request.getMethod() + " " + pattern)) return true;
        throw new ApiException(ErrorCode.AUTH_ACCOUNT_SUSPENDED);
    }

    static boolean isSuspended(Authentication authentication) {
        if (authentication == null) return false;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (SUSPENDED_MARKER.equals(authority.getAuthority())) return true;
        }
        return false;
    }
}
