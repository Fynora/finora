package com.finora.observability;

import com.finora.entity.User;
import com.finora.service.AuthorizationService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.UUID;

/**
 * Feeds {@link UserRequestLoad} one count per request made by a signed-in account. It observes and
 * never refuses: {@code preHandle} always returns true, and nothing here can throw.
 *
 * <p>Only the first dispatch of a request is counted ({@link DispatcherType#REQUEST}). An async
 * request or an error page is dispatched again inside the same request, and counting those would
 * make one request look like two.
 *
 * <p>Requests with no signed-in account (login, refresh, the public pages) carry no user to count
 * and are skipped; they are covered by the per-IP limits in the rate-limit filter.
 *
 * <p>Temporary, with {@link UserRequestLoad}.
 */
@Component
public class UserRequestLoadInterceptor implements HandlerInterceptor {

    private static final String USER_PORTAL_AUTHORITY = AuthorizationService.portalAuthority(User.SCOPE_USER);
    private static final String ADMIN_PORTAL_AUTHORITY = AuthorizationService.portalAuthority(User.SCOPE_ADMIN);

    private final UserRequestLoad load;

    public UserRequestLoadInterceptor(UserRequestLoad load) {
        this.load = load;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        try {
            if (request.getDispatcherType() != DispatcherType.REQUEST) return true;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails principal)) {
                return true;
            }
            String portal = portalOf(authentication);
            if (portal == null) return true;
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            load.record(UUID.fromString(principal.getUsername()), portal,
                    pattern instanceof String route ? route : null);
        } catch (RuntimeException e) {
            // A username that is not a user id, or anything unforeseen: a measurement must never
            // fail the request it is measuring.
        }
        return true;
    }

    private static String portalOf(Authentication authentication) {
        boolean user = false;
        boolean admin = false;
        for (var authority : authentication.getAuthorities()) {
            if (USER_PORTAL_AUTHORITY.equals(authority.getAuthority())) user = true;
            if (ADMIN_PORTAL_AUTHORITY.equals(authority.getAuthority())) admin = true;
        }
        // An account holding both is counted as the customer it is; admin tooling is the bursty one.
        if (user) return "user";
        return admin ? "admin" : null;
    }
}
