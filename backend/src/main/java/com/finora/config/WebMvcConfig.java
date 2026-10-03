package com.finora.config;

import com.finora.security.AdminUserDataReadAuditInterceptor;
import com.finora.security.UserActivityInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC-level registrations: the admin read-audit interceptor and the user activity-day recorder.
 * See each one's own doc comment for why it is an interceptor rather than a call in each controller.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Every route that names one user. The bare {@code /api/v1/admin/users} listing is
     *  deliberately not here: it discloses a page of users, not one user's data, and has no single
     *  target id to attribute the read to. */
    static final String[] ADMIN_SINGLE_USER_ROUTES = {
            "/api/v1/admin/users/*",
            "/api/v1/admin/users/*/**",
    };

    /** Every API route. The interceptor itself decides whose requests count (user-portal accounts
     *  only); this just keeps it off non-API paths such as error pages. */
    static final String[] USER_ACTIVITY_ROUTES = {"/api/**"};

    private final AdminUserDataReadAuditInterceptor adminUserDataReadAuditInterceptor;
    private final UserActivityInterceptor userActivityInterceptor;

    public WebMvcConfig(AdminUserDataReadAuditInterceptor adminUserDataReadAuditInterceptor,
                        UserActivityInterceptor userActivityInterceptor) {
        this.adminUserDataReadAuditInterceptor = adminUserDataReadAuditInterceptor;
        this.userActivityInterceptor = userActivityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminUserDataReadAuditInterceptor).addPathPatterns(ADMIN_SINGLE_USER_ROUTES);
        registry.addInterceptor(userActivityInterceptor).addPathPatterns(USER_ACTIVITY_ROUTES);
    }
}
