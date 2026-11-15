package com.finora.architecture;

import com.finora.AbstractIntegrationTest;
import com.finora.architecture.AdminGuardRule.Violation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.finora.architecture.AdminGuardRule.ADMIN_PATH_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule FG-024 against the routes Spring actually registered in the running application, rather
 * than the routes {@link AdminEndpointAuthorizationTest} works out from compiled classes. Whatever
 * way a route is declared -- on the class, on the method, inherited, composed, on an interface --
 * Spring's own handler registry lists it here with its final path and the method that serves it,
 * so a declaration style the static rule does not model cannot slip past both.
 *
 * <p>The architecture-test fixtures are on the test classpath and so are registered too: they are
 * this test's self-check. The rule must flag exactly them, which proves it detects the shapes it
 * exists to catch, and nothing in production code.
 */
class AdminEndpointAuthorizationIT extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private List<Violation> violations() {
        List<Violation> found = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!isAdmin(entry.getKey())) continue;
            HandlerMethod handler = entry.getValue();
            Class<?> controller = ClassUtils.getUserClass(handler.getBeanType());
            // A method's own @PreAuthorize replaces the class's (Spring Security's own rule).
            PreAuthorize own = AnnotatedElementUtils.getMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            PreAuthorize inherited = AnnotatedElementUtils.getMergedAnnotation(controller, PreAuthorize.class);
            String guard = own != null ? own.value() : inherited != null ? inherited.value() : null;
            AdminGuardRule.check(controller.getSimpleName() + "." + handler.getMethod().getName() + "()", guard)
                    .ifPresent(found::add);
        }
        return found;
    }

    private static boolean isAdmin(RequestMappingInfo info) {
        return info.getPatternValues().stream().anyMatch(pattern -> pattern.startsWith(ADMIN_PATH_PREFIX));
    }

    @Test
    void everyRegisteredAdminRouteHasAGuardOnlyAnAdminCanMeet() {
        assertThat(violations())
                .as("registered admin routes an ordinary customer could call (only the deliberate "
                        + "architecture-test fixtures may appear here); see AdminGuardRule")
                .extracting(Violation::handler)
                .containsExactlyInAnyOrderElementsOf(AdminGuardRule.FIXTURE_VIOLATIONS);
    }

    /** If the registry lookup silently matched nothing, the assertion above would prove nothing. */
    @Test
    void theRegistryListsTheWholeAdminSurface() {
        Set<String> adminPatterns = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .filter(pattern -> pattern.startsWith(ADMIN_PATH_PREFIX))
                .forEach(adminPatterns::add);

        assertThat(adminPatterns)
                .hasSizeGreaterThan(150)
                .contains("/api/v1/admin/users/{userId}/capability-coverage", "/api/v1/admin/decision-queue");
    }
}
