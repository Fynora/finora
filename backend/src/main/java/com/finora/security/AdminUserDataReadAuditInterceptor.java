package com.finora.security;

import com.finora.security.AdminReadAuditPolicy.Kind;
import com.finora.security.AdminReadAuditPolicy.Rule;
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
import java.util.TreeMap;
import java.util.UUID;

/**
 * Records an audit row every time an admin READS customer data.
 *
 * <p>Every admin mutation is audited by the service that performs it. Reads were audited only
 * under {@code /api/v1/admin/users/{userId}/...} (audit, 2026-09-24), while a held import, a
 * statement's rows, a transaction trace, a page of a statement's original, or a listing of
 * customers' emails and file names could be read under other paths with no trace. The Privacy
 * Policy says every administrative access is logged; privacy workstream 9 made that true for reads.
 *
 * <p>What each route discloses is decided in {@link AdminReadAuditPolicy}, one table:
 * <ul>
 *   <li>one person's data: {@value #ACTION}, against the admin, naming that person as the entity
 *       ({@link AdminReadOwnerResolver} finds them from the id in the request), so "who has looked
 *       at this person's data" is one query on {@code entity_id}. Some of these routes also write
 *       their own, more specific row (for example {@code HELD_IMPORT_VIEWED}); this one is the
 *       uniform per-person record.</li>
 *   <li>a listing of several people's data: {@value #LISTED_ACTION}, with the endpoint and the
 *       filters used, and no single person.</li>
 *   <li>counts, configuration and health: nothing.</li>
 * </ul>
 * A route the table does not name is recorded as a listing, marked {@code unclassified}, so a gap
 * shows up as an over-recorded read rather than an unrecorded one;
 * {@code AdminReadAuditPolicyCoverageTest} keeps that from happening in the first place.
 *
 * <p>Only successful responses are recorded: a 403 or 404 disclosed nothing, and the
 * authorization failure is already logged by Spring Security. Ids come from the matched URI
 * template, never from the raw URI, and the metadata records the matched pattern rather than the
 * request line for the same reason.
 *
 * <p>An audit failure here must never fail the response the admin already received, so the
 * write is wrapped and logged; the row is evidence, not a gate.
 */
@Component
public class AdminUserDataReadAuditInterceptor implements HandlerInterceptor {

    public static final String ACTION = "ADMIN_USER_DATA_VIEWED";
    public static final String LISTED_ACTION = "ADMIN_USER_DATA_LISTED";

    private static final Logger log = LoggerFactory.getLogger(AdminUserDataReadAuditInterceptor.class);

    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final AdminReadOwnerResolver ownerResolver;

    public AdminUserDataReadAuditInterceptor(AuditService auditService, CurrentUser currentUser,
                                             AdminReadOwnerResolver ownerResolver) {
        this.auditService = auditService;
        this.currentUser = currentUser;
        this.ownerResolver = ownerResolver;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, @Nullable Exception ex) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return;
        if (response.getStatus() < 200 || response.getStatus() >= 300) return;
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern = matched == null ? null : matched.toString();
        if (pattern == null) return;
        Rule rule = AdminReadAuditPolicy.forPattern(pattern).orElse(null);
        if (rule != null && rule.kind() == Kind.NO_PERSONAL_DATA) return;
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("endpoint", pattern);
            metadata.put("status", response.getStatus());
            if (rule != null && rule.kind() == Kind.ONE_USER) {
                String value = variableOrParameter(request, rule.variable());
                // The id the admin asked for, so the exact item is traceable; never the email a
                // lookup by email was made with.
                if (rule.owner() != AdminReadAuditPolicy.Owner.USER_EMAIL_PARAM && value != null) {
                    metadata.put(rule.variable(), value);
                }
                UUID owner = ownerResolver.ownerOf(rule.owner(), value);
                auditService.record(currentUser.id(), ACTION, "User", owner, metadata);
            } else {
                if (rule == null) {
                    metadata.put("unclassified", true);
                    log.warn("Admin GET {} is not in AdminReadAuditPolicy; recorded as a listing.", pattern);
                }
                metadata.put("query", new TreeMap<>(request.getParameterMap().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                e -> String.join(",", e.getValue())))));
                auditService.record(currentUser.id(), LISTED_ACTION, "User", null, metadata);
            }
        } catch (RuntimeException e) {
            log.error("Could not record an admin read audit row for {}; the response was already "
                    + "served and is unaffected.", pattern, e);
        }
    }

    @Nullable
    private static String variableOrParameter(HttpServletRequest request, String name) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map && map.get(name) != null) return map.get(name).toString();
        return request.getParameter(name);
    }
}
