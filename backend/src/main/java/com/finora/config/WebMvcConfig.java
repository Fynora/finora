package com.finora.config;

import com.finora.security.AdminUserDataReadAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC-level registrations. Only the admin read-audit interceptor lives here today; see its own
 * doc comment for why it is an interceptor rather than a call in each controller.
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

    private final AdminUserDataReadAuditInterceptor adminUserDataReadAuditInterceptor;

    public WebMvcConfig(AdminUserDataReadAuditInterceptor adminUserDataReadAuditInterceptor) {
        this.adminUserDataReadAuditInterceptor = adminUserDataReadAuditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminUserDataReadAuditInterceptor).addPathPatterns(ADMIN_SINGLE_USER_ROUTES);
    }
}
