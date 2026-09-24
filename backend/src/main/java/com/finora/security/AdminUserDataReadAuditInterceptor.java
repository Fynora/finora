package com.finora.security;

import com.finora.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records an audit row every time an admin READS one specific user's data.
 *
 * <p>Every admin mutation is audited by the service that performs it, and the held-statement
 * document download records {@code TRUST_REVIEW_DOCUMENT_DOWNLOADED} -- but a plain GET of a
 * user's transactions, workspace, analytics, merchants or relationships left no trace at all
 * (audit, 2026-09-24). For a financial product that is the one access pattern an insider-misuse
 * review actually asks about. An interceptor rather than a call in each controller so that the
 * next {@code /api/v1/admin/users/{userId}/...} endpoint inherits it instead of remembering it;
 * {@code WebMvcConfig} registers it on exactly that path family.
 *
 * <p>Only successful responses are recorded: a 403 or 404 disclosed nothing, and the
 * authorization failure is already logged by Spring Security. The target user id comes from the
 * matched URI template, never from the raw URI, and the metadata records the matched pattern
 * rather than the request line for the same reason.
 *
 * <p>An audit failure here must never fail the response the admin already received, so the
 * write is wrapped and logged; the row is evidence, not a gate.
 */
@Component
public class AdminUserDataReadAuditInterceptor implements HandlerInterceptor {

    public static final String ACTION = "ADMIN_USER_DATA_VIEWED";

    private static final Logger log = LoggerFactory.getLogger(AdminUserDataReadAuditInterceptor.class);

    private final AuditService auditService;
    private final CurrentUser currentUser;

    public AdminUserDataReadAuditInterceptor(AuditService auditService, CurrentUser currentUser) {
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, @Nullable Exception ex) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return;
        if (response.getStatus() < 200 || response.getStatus() >= 300) return;
        UUID targetUserId = targetUserId(request);
        if (targetUserId == null) return;
        try {
            Map<String, Object> metadata = new HashMap<>();
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            metadata.put("endpoint", pattern == null ? "unknown" : pattern.toString());
            metadata.put("status", response.getStatus());
            auditService.record(currentUser.id(), ACTION, "User", targetUserId, metadata);
        } catch (RuntimeException e) {
            log.error("Could not record an {} audit row for target user {}; the response was already "
                    + "served and is unaffected.", ACTION, targetUserId, e);
        }
    }

    @Nullable
    private static UUID targetUserId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) return null;
        Object raw = map.get("userId");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
