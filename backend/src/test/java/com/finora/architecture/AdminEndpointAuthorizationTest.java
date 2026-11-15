package com.finora.architecture;

import com.finora.architecture.AdminGuardRule.Violation;
import com.finora.architecture.registry.GuardianSelfTest;
import com.finora.architecture.registry.GuardianRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static com.finora.architecture.AdminGuardRule.ADMIN_PATH_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable security rule, not a point patch.
 *
 * <p>The bug this exists to prevent has already happened once: {@code AdminSearchController}
 * shipped with no {@code @PreAuthorize} at all, on the mistaken theory that SecurityConfig's
 * {@code anyRequest().authenticated()} already restricted {@code /api/v1/admin/**} to admins. It
 * does not -- SecurityConfig has NO admin-path authorization whatsoever, so that rule is satisfied
 * by any valid JWT including an ordinary consumer-app user's own login. Authorization for the
 * entire admin surface is delegated, endpoint by endpoint, to {@code @PreAuthorize}. That makes an
 * omitted annotation silently equivalent to "any logged-in user may call this" -- which is exactly
 * how a non-admin came to be able to read other users' names and email addresses.
 *
 * <p>Fixing that one controller does not stop the 47th admin controller from repeating it. This
 * test does: it fails the build the moment any admin-mapped handler is added without a guard, or
 * with a guard an ordinary customer can meet (see {@link AdminGuardRule}). It is deliberately scoped
 * to {@code /api/v1/admin} paths -- consumer endpoints are user-scoped and enforce access through
 * {@code CurrentUser} plus per-row ownership checks (see {@code OwnershipGuard}), not authorities,
 * so requiring {@code @PreAuthorize} on those would be wrong rather than merely noisy.
 *
 * <p>This reads compiled classes, so it fails fast without a database. {@link AdminEndpointAuthorizationIT}
 * applies the same rule to the routes Spring actually registers, which also covers any way of
 * declaring a route this file does not model (an inherited handler, say).
 */
class AdminEndpointAuthorizationTest {

    private JavaClasses productionClasses() {
        return ProductionClasses.INSTANCE;
    }

    /**
     * The rule itself, factored out so the exact same logic can be pointed at the deliberately
     * vulnerable fixtures below -- see {@link #theRuleDetectsUnguardedAndWeaklyGuardedAdminEndpoints()}.
     */
    private List<Violation> findViolations(JavaClasses classes) {
        List<Violation> violations = new ArrayList<>();
        for (JavaClass controller : classes) {
            if (!isController(controller)) continue;
            String classGuard = controller.tryGetAnnotationOfType(PreAuthorize.class).map(PreAuthorize::value).orElse(null);
            for (JavaMethod method : adminHandlers(controller)) {
                // A method's own @PreAuthorize replaces the class's; without one, the class's applies.
                String guard = method.tryGetAnnotationOfType(PreAuthorize.class).map(PreAuthorize::value).orElse(classGuard);
                AdminGuardRule.check(controller.getSimpleName() + "." + method.getName() + "()", guard)
                        .ifPresent(violations::add);
            }
        }
        return violations;
    }

    /**
     * The handlers of {@code controller} that Spring serves under the admin prefix: class path joined
     * with method path, as Spring itself joins them. Looking at the class path alone missed an admin
     * route declared on the method of a consumer-prefixed class ({@code CapabilityCoverageController}
     * maps {@code /api/v1} and one method {@code /admin/users/{userId}/...}) and skipped classes with
     * no class-level mapping entirely -- an unguarded handler written either way passed the build.
     * Mappings are read the way Spring reads them (merged, so a composed annotation or one declared
     * on an interface method counts).
     */
    private List<JavaMethod> adminHandlers(JavaClass controller) {
        List<String> basePaths = paths(AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class));
        List<JavaMethod> admin = new ArrayList<>();
        for (JavaMethod method : controller.getMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method.reflect(), RequestMapping.class);
            if (mapping == null) continue;
            List<String> methodPaths = paths(mapping);
            boolean isAdmin = basePaths.stream()
                    .anyMatch(base -> methodPaths.stream().anyMatch(path -> join(base, path).startsWith(ADMIN_PATH_PREFIX)));
            if (isAdmin) admin.add(method);
        }
        return admin;
    }

    /** A mapping's paths ({@code value} and {@code path} are aliases once merged); none means the bare prefix. */
    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) return List.of("");
        return List.of(mapping.path());
    }

    /** Joins as Spring does: one leading slash, no doubled slashes ({@code "api/v1/" + "/admin"} is {@code /api/v1/admin}). */
    static String join(String base, String path) {
        return ("/" + base + "/" + path).replaceAll("/+", "/");
    }

    @GuardianRule(
            id = "FG-024",
            category = GuardianRule.Category.SECURITY,
            intent = "Every admin endpoint carries an authorization annotation an ordinary customer cannot meet.",
            source = "Incident: unguarded admin endpoint",
            introduced = "2026-08-01",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    void everyAdminEndpointIsGuardedByAnAuthorizationAnnotation() {
        assertThat(findViolations(productionClasses()))
                .as("""
                        Admin endpoints an ordinary customer could call. SecurityConfig only enforces
                        anyRequest().authenticated() -- it has no admin-path rule -- so each admin handler
                        needs @PreAuthorize with hasAuthority/hasAnyAuthority/hasRole/hasAnyRole checks
                        (joined by 'and') naming an admin permission or role, on the method or on the
                        controller class. See AdminGuardRule.""")
                .isEmpty();
    }

    /**
     * Guards the guard, part one: proves the rule genuinely detects the bug shapes it exists to
     * catch, by running the identical logic against fixtures that deliberately reproduce them. A
     * security rule that silently stopped detecting anything would otherwise look exactly like a
     * clean codebase.
     */
    @GuardianSelfTest(rule = "FG-024")
    @Test
    void theRuleDetectsUnguardedAndWeaklyGuardedAdminEndpoints() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("com.finora.architecture.fixtures");

        assertThat(findViolations(fixtures))
                .as("the rule must flag every unguarded or weakly guarded admin handler -- admin path on the "
                        + "class, on the method only, or on a class with no base path; a weak method guard "
                        + "replacing a strong class guard -- and must NOT flag guarded siblings or a consumer route")
                .extracting(Violation::handler)
                .containsExactlyInAnyOrderElementsOf(AdminGuardRule.FIXTURE_VIOLATIONS);
    }

    /**
     * Guards the guard, part two: if the detection logic silently stopped matching anything (a
     * package move, a renamed annotation, an ArchUnit upgrade that changes annotation reflection),
     * the production assertion would keep passing while checking nothing at all. Asserting a
     * realistic floor means that failure mode surfaces as a red test rather than false confidence.
     */
    @GuardianSelfTest(rule = "FG-024")
    @Test
    void theRuleActuallyFindsTheAdminControllersItClaimsToCheck() {
        List<String> adminHandlers = productionClasses().stream()
                .filter(this::isController)
                .flatMap(controller -> adminHandlers(controller).stream()
                        .map(method -> controller.getSimpleName() + "." + method.getName() + "()"))
                .toList();
        long adminControllers = adminHandlers.stream().map(h -> h.substring(0, h.indexOf('.'))).distinct().count();

        assertThat(adminControllers)
                .as("expected to find the admin controller surface; if this dropped to ~0 the "
                        + "authorization rule above is silently checking nothing")
                .isGreaterThanOrEqualTo(50);
        assertThat(adminHandlers)
                .as("an admin route declared on the method of a consumer-prefixed class must be found too")
                .contains("CapabilityCoverageController.forUser()");
    }

    @GuardianSelfTest(rule = "FG-024")
    @Test
    void pathsJoinTheWaySpringJoinsThem() {
        assertThat(join("/api/v1/", "/admin/x")).isEqualTo("/api/v1/admin/x");
        assertThat(join("api/v1", "admin")).isEqualTo("/api/v1/admin");
        assertThat(join("", "/api/v1/admin/x")).isEqualTo("/api/v1/admin/x");
        assertThat(join("/api/v1/admin", "")).isEqualTo("/api/v1/admin/");
        assertThat(join("/api/v1/admin", "").startsWith(ADMIN_PATH_PREFIX)).isTrue();
    }

    /** {@code @RestController}, or a plain {@code @Controller}, which can serve data the same way. */
    private boolean isController(JavaClass candidate) {
        return candidate.isAnnotatedWith(RestController.class) || candidate.isAnnotatedWith(Controller.class);
    }
}
