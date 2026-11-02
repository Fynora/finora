package com.finora.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every admin GET route is classified in {@link AdminReadAuditPolicy}, and the table names no route
 * that no longer exists. Privacy workstream 9: a new admin endpoint that returns customer data must
 * not ship without its read being recorded, and the way to stop that is to make leaving it out of
 * the table fail the build. If this fails for a route you added, decide what it discloses and add
 * it to {@code AdminReadAuditPolicy.ROUTES}.
 */
class AdminReadAuditPolicyCoverageTest {

    private static final String ADMIN_PREFIX = "/api/v1/admin/";

    /** Every GET route under the admin prefix, as Spring matches it: class path + method path. */
    private static Set<String> adminGetRoutes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> routes = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.finora")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            // Test-only controllers (architecture-test fixtures) are on the test classpath too.
            if (controller.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes")) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] basePaths = base == null || base.path().length == 0 ? new String[]{""} : base.path();
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || !Arrays.asList(mapping.method()).contains(RequestMethod.GET)) continue;
                String[] subPaths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                for (String basePath : basePaths) {
                    for (String subPath : subPaths) {
                        String route = basePath + subPath;
                        if (route.startsWith(ADMIN_PREFIX) || route.equals("/api/v1/admin")) routes.add(route);
                    }
                }
            }
        }
        return routes;
    }

    @Test
    void everyAdminGetRouteIsClassified() throws Exception {
        Set<String> routes = adminGetRoutes();
        // The scan must actually find the admin controllers, or this test proves nothing.
        assertThat(routes).hasSizeGreaterThan(90).contains("/api/v1/admin/held-imports/{jobId}");

        Set<String> unclassified = new TreeSet<>();
        for (String route : routes) {
            if (AdminReadAuditPolicy.forPattern(route).isEmpty()) unclassified.add(route);
        }
        assertThat(unclassified).as("admin GET routes missing from AdminReadAuditPolicy").isEmpty();
    }

    @Test
    void theTableNamesNoRouteThatDoesNotExist() throws Exception {
        Set<String> routes = adminGetRoutes();
        Set<String> stale = new TreeSet<>(AdminReadAuditPolicy.ROUTES.keySet());
        stale.removeAll(routes);
        assertThat(stale).as("AdminReadAuditPolicy entries with no matching admin GET route").isEmpty();
    }
}
