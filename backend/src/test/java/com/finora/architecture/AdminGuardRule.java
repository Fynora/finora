package com.finora.architecture;

import com.finora.entity.User;
import com.finora.security.SuspendedAccountInterceptor;
import com.finora.service.AuthorizationService;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What counts as a strong enough guard on an admin endpoint (rule FG-024), shared by the build-time
 * check ({@link AdminEndpointAuthorizationTest}) and the runtime one ({@link AdminEndpointAuthorizationIT})
 * so the two cannot drift apart.
 *
 * <p>A consumer (USER-scope) account holds no permissions at all, whatever its roles say
 * ({@code AuthorizationService.addRole} returns before adding any): only {@code ROLE_<role>}, its
 * portal marker and, when suspended, the suspension marker. So a guard is safe when every branch of it
 * needs a permission or an admin role -- and unsafe the moment any part of it can be met by a
 * customer. Rather than list the bad shapes ({@code isAuthenticated()}, {@code permitAll()},
 * {@code hasRole('USER')}, an {@code or} with an ownership check, a negation...), the rule accepts one
 * shape only: authority or role checks joined by {@code and}, naming nothing a customer holds. Every
 * guard on the admin surface already has that shape; anything else fails with a message saying how
 * to write it.
 */
final class AdminGuardRule {

    static final String ADMIN_PATH_PREFIX = "/api/v1/admin";

    /** Every authority name a consumer account can carry, derived from the code that grants them. */
    static final Set<String> CUSTOMER_AUTHORITIES = Set.of(
            User.DEFAULT_ROLE,
            "ROLE_" + User.DEFAULT_ROLE,
            AuthorizationService.portalAuthority(User.SCOPE_USER),
            SuspendedAccountInterceptor.SUSPENDED_MARKER);

    /** The handlers in {@code com.finora.architecture.fixtures} the rule must flag, and no others. */
    static final Set<String> FIXTURE_VIOLATIONS = Set.of(
            "UnguardedAdminControllerFixture.unguardedHandler()",
            "MethodPathAdminControllerFixture.unguardedAdminHandler()",
            "NoBasePathAdminControllerFixture.unguardedHandler()",
            "WeakGuardAdminControllerFixture.anySignedInAccount()",
            "WeakGuardAdminControllerFixture.customerRole()",
            "WeakGuardAdminControllerFixture.eitherPermission()");

    private static final String NAME = "'[A-Za-z0-9_]+'";
    private static final String CHECK = "(?:hasAuthority|hasAnyAuthority|hasRole|hasAnyRole)\\(\\s*" + NAME + "(?:\\s*,\\s*" + NAME + ")*\\s*\\)";
    private static final Pattern ALLOWED = Pattern.compile("\\s*" + CHECK + "(?:\\s+and\\s+" + CHECK + ")*\\s*");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private AdminGuardRule() {
    }

    /** One finding: the handler ({@code Class.method()}) and what is wrong with it. */
    record Violation(String handler, String problem) {
        @Override
        public String toString() {
            return handler + ": " + problem;
        }
    }

    /**
     * Checks the guard that actually applies to a handler -- its own {@code @PreAuthorize} if it has
     * one (that replaces the class's), else the class's -- and returns what is wrong, if anything.
     */
    static Optional<Violation> check(String handler, String effectiveGuard) {
        if (effectiveGuard == null) {
            return Optional.of(new Violation(handler, "no @PreAuthorize, so any signed-in customer can call it"));
        }
        return weakness(effectiveGuard).map(why -> new Violation(handler, why + " -- @PreAuthorize(\"" + effectiveGuard + "\")"));
    }

    /** Why {@code expression} could let an ordinary customer through, or empty when it cannot. */
    static Optional<String> weakness(String expression) {
        if (!ALLOWED.matcher(expression).matches()) {
            return Optional.of("guard is not hasAuthority/hasAnyAuthority/hasRole/hasAnyRole checks joined by 'and' "
                    + "(use hasAnyAuthority(...) instead of 'or'; nothing else can be proved to exclude customers)");
        }
        Matcher quoted = QUOTED.matcher(expression);
        while (quoted.find()) {
            if (CUSTOMER_AUTHORITIES.contains(quoted.group(1))) {
                return Optional.of("guard names " + quoted.group(1) + ", which an ordinary customer account holds");
            }
        }
        return Optional.empty();
    }
}
