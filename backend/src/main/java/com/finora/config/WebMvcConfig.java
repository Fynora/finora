package com.finora.config;

import com.finora.observability.UserRequestLoadInterceptor;
import com.finora.security.AdminUserDataReadAuditInterceptor;
import com.finora.security.SuspendedAccountInterceptor;
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

    /** Every admin route. The interceptor records only GETs, and only those that
     *  {@code AdminReadAuditPolicy} says disclose customer data (privacy workstream 9). */
    static final String[] ADMIN_READ_AUDIT_ROUTES = {"/api/v1/admin/**"};

    /** Every API route. The interceptor itself decides whose requests count (user-portal accounts
     *  only); this just keeps it off non-API paths such as error pages. */
    static final String[] USER_ACTIVITY_ROUTES = {"/api/**"};

    /** Every API route: a suspended account is refused everywhere except the few routes
     *  {@code SuspendedAccountInterceptor} allows (privacy workstream 10). */
    static final String[] SUSPENDED_ACCOUNT_ROUTES = {"/api/**"};

    private final SuspendedAccountInterceptor suspendedAccountInterceptor;
    private final AdminUserDataReadAuditInterceptor adminUserDataReadAuditInterceptor;
    private final UserActivityInterceptor userActivityInterceptor;
    private final UserRequestLoadInterceptor userRequestLoadInterceptor;

    public WebMvcConfig(SuspendedAccountInterceptor suspendedAccountInterceptor,
                        AdminUserDataReadAuditInterceptor adminUserDataReadAuditInterceptor,
                        UserActivityInterceptor userActivityInterceptor,
                        UserRequestLoadInterceptor userRequestLoadInterceptor) {
        this.suspendedAccountInterceptor = suspendedAccountInterceptor;
        this.adminUserDataReadAuditInterceptor = adminUserDataReadAuditInterceptor;
        this.userActivityInterceptor = userActivityInterceptor;
        this.userRequestLoadInterceptor = userRequestLoadInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // First, so a refused request never reaches the others (activity counting, read audit).
        registry.addInterceptor(suspendedAccountInterceptor).addPathPatterns(SUSPENDED_ACCOUNT_ROUTES);
        registry.addInterceptor(adminUserDataReadAuditInterceptor).addPathPatterns(ADMIN_READ_AUDIT_ROUTES);
        registry.addInterceptor(userActivityInterceptor).addPathPatterns(USER_ACTIVITY_ROUTES);
        // Measures per-user request rates, refuses nothing (see UserRequestLoad).
        registry.addInterceptor(userRequestLoadInterceptor).addPathPatterns(USER_ACTIVITY_ROUTES);
    }
}
