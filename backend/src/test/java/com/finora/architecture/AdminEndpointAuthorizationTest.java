package com.finora.architecture;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

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
 * test does: it fails the build the moment any admin-mapped handler method is added without an
 * authorization annotation covering it, at class level or method level. It is deliberately scoped
 * to {@code /api/v1/admin} paths -- consumer endpoints are user-scoped and enforce access through
 * {@code CurrentUser} plus per-row ownership checks (see {@code OwnershipGuard}), not authorities,
 * so requiring {@code @PreAuthorize} on those would be wrong rather than merely noisy.
 */
class AdminEndpointAuthorizationTest {

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";

    /** Every annotation Spring treats as declaring a request-handling method. */
    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, DeleteMapping.class, PatchMapping.class);

    private JavaClasses productionClasses() {
        return ProductionClasses.INSTANCE;
    }

    /**
     * The rule itself, factored out so the exact same logic can be pointed at the deliberately
     * vulnerable fixture below -- see {@link #theRuleDetectsAnUnguardedAdminEndpoint()}.
     */
    private List<String> findUnguardedAdminHandlers(JavaClasses classes) {
        List<String> unguarded = new ArrayList<>();

        for (JavaClass controller : classes) {
            if (!isController(controller)) continue;
            // A class-level @PreAuthorize covers every handler in the class -- the majority
            // style in this codebase (see AdminBankController). Only classes without one need
            // each individual method checked.
            boolean classGuarded = controller.isAnnotatedWith(PreAuthorize.class);

            for (JavaMethod method : adminHandlers(controller)) {
                if (classGuarded || method.isAnnotatedWith(PreAuthorize.class)) continue;
                unguarded.add(controller.getSimpleName() + "." + method.getName() + "()");
            }
        }
        return unguarded;
    }

    /**
     * The handlers of {@code controller} that Spring serves under the admin prefix: class path joined
     * with method path, as Spring itself joins them. Looking at the class path alone missed an admin
     * route declared on the method of a consumer-prefixed class ({@code CapabilityCoverageController}
     * maps {@code /api/v1} and one method {@code /admin/users/{userId}/...}) and skipped classes with
     * no class-level mapping entirely -- an unguarded handler written either way passed the build.
     */
    private List<JavaMethod> adminHandlers(JavaClass controller) {
        List<String> basePaths = paths(AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class));
        List<JavaMethod> admin = new ArrayList<>();
        for (JavaMethod method : controller.getMethods()) {
            if (!isRequestHandler(method)) continue;
            List<String> methodPaths = paths(AnnotatedElementUtils.findMergedAnnotation(method.reflect(), RequestMapping.class));
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

    private static String join(String base, String path) {
        if (path.isEmpty()) return base;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    @GuardianRule(
            id = "FG-024",
            category = GuardianRule.Category.SECURITY,
            intent = "Every admin endpoint carries an authorization annotation.",
            source = "Incident: unguarded admin endpoint",
            introduced = "2026-08-01",
            owner = "architecture",
            verification = GuardianRule.Verification.SELF_TEST)
    @Test
    void everyAdminEndpointIsGuardedByAnAuthorizationAnnotation() {
        assertThat(findUnguardedAdminHandlers(productionClasses()))
                .as("""
                        Admin endpoints reachable by ANY authenticated user, including non-admins.
                        SecurityConfig only enforces anyRequest().authenticated() -- it has no
                        admin-path rule -- so an admin handler without @PreAuthorize is open to
                        every logged-in consumer account. Add @PreAuthorize("hasAuthority('...')")
                        to the method, or to the controller class to cover all of its handlers.""")
                .isEmpty();
    }

    /**
     * Guards the guard, part one: proves the rule genuinely detects the bug shape it exists to
     * catch, by running the identical logic against a fixture that deliberately reproduces the
     * original AdminSearchController vulnerability. A security rule that silently stopped
     * detecting anything would otherwise look exactly like a clean codebase.
     */
    @GuardianSelfTest(rule = "FG-024")
    @Test
    void theRuleDetectsAnUnguardedAdminEndpoint() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("com.finora.architecture.fixtures");

        assertThat(findUnguardedAdminHandlers(fixtures))
                .as("the rule must flag every unguarded admin handler -- admin path on the class, on "
                        + "the method only, or on a class with no base path -- and must NOT flag the "
                        + "correctly-@PreAuthorize'd siblings or a consumer route")
                .containsExactlyInAnyOrder(
                        "UnguardedAdminControllerFixture.unguardedHandler()",
                        "MethodPathAdminControllerFixture.unguardedAdminHandler()",
                        "NoBasePathAdminControllerFixture.unguardedHandler()");
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

    /** {@code @RestController}, or a plain {@code @Controller}, which can serve data the same way. */
    private boolean isController(JavaClass candidate) {
        return candidate.isAnnotatedWith(RestController.class) || candidate.isAnnotatedWith(Controller.class);
    }

    private boolean isRequestHandler(JavaMethod method) {
        return MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith);
    }
}
