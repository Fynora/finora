package com.finora.controller;

import com.finora.dto.PagedResponse;
import com.finora.dto.ApiResponse;
import com.finora.activation.ReportingDay;
import com.finora.dto.AdminAuditLogRowDto;
import com.finora.dto.AuditLogDto;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.security.CurrentUser;
import com.finora.repository.UserRepository;
import com.finora.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.finora.util.LikePatterns;

/**
 * The first real RBAC-gated endpoint — everything else in the API only ever operates on the
 * calling user's own data (enforced via CurrentUser, not roles), so there was nothing for
 * @PreAuthorize to meaningfully protect until now. Viewing another user's audit trail is exactly
 * the kind of cross-user access that should require an elevated role, not just "any authenticated
 * user" — a real admin/support use case (investigating a reported incident), not a demo stub.
 *
 * Gated by the AUDIT_VIEW permission rather than hasRole('ADMIN') (docs/engineering-directive-
 * phase1.md, Priority 2: "permissions should control access instead of hardcoded role checks").
 * A user whose only access is the legacy `role = "ADMIN"` string still gets this exactly as
 * before -- V16__rbac_roles_permissions.sql seeds an ADMIN Role that includes AUDIT_VIEW, and
 * AuthorizationService resolves that legacy string against it automatically, so this change is
 * behavior-preserving, not a silent tightening or loosening of who can reach this endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
public class AdminController {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final ReportingDay reportingDay;

    public AdminController(AuditLogRepository auditLogRepository, UserRepository userRepository,
                           CurrentUser currentUser, ReportingDay reportingDay) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.reportingDay = reportingDay;
    }

    @GetMapping("/users/{userId}/audit-logs")
    public ApiResponse<List<AuditLogDto>> auditLogsForUser(@PathVariable UUID userId) {
        var logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
        return ApiResponse.ok(logs);
    }

    /**
     * The global (cross-user) activity feed for the admin portal's Audit Log page -- distinct
     * from auditLogsForUser above, which is scoped to one account someone is specifically
     * investigating. Paginated from the start (see AuditLogRepository.search) since this has no
     * natural upper bound the way a single user's history does.
     *
     * Admin Portal Phase 5 (Shared Filtering Framework) -- q/dateFrom/dateTo/sort are all
     * optional, same null-means-"don't filter" convention TransactionController.search and
     * UserRepository.search already use. dateTo is treated as inclusive of the whole day (rolled
     * forward to the next day's midnight) rather than midnight of that day itself, since an admin
     * picking "to: today" in a date-range filter expects today's entries to actually show up.
     */
    @GetMapping("/audit-logs")
    public ApiResponse<PagedResponse<AdminAuditLogRowDto>> globalAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String requestId) {
        int safeSize = com.finora.util.PageBounds.safeSize(size);
        int safePage = com.finora.util.PageBounds.safePage(page);
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(direction, "createdAt", "id"));

        // Escaped for LIKE -- see LikePatterns. The audit Activity Feed searches action and
        // entityType, where an underscore is ordinary ("USER_LOGIN"), so an unescaped _ made
        // every such search quietly over-match.
        String trimmedQ = (q != null && !q.isBlank()) ? LikePatterns.escape(q.trim()) : null;
        // Days are the platform reporting zone's (IST by default), the same days the dashboard and
        // the page's own day headings use. These were UTC midnights, so "to: today" left out
        // everything from 00:00 to 05:30 IST on the following morning and "from: today" pulled in
        // the previous evening.
        ZoneId zone = reportingDay.zone();
        Instant from = dateFrom != null ? dateFrom.atStartOfDay(zone).toInstant() : null;
        Instant to = dateTo != null ? dateTo.plusDays(1).atStartOfDay(zone).toInstant() : null;

        var pageResult = auditLogRepository.search(trimmedQ, from, to, userId,
                actorId != null ? actorId.toString() : null, blankToNull(action), blankToNull(requestId), pageable);
        Map<UUID, User> people = peopleNamedIn(pageResult.getContent());
        boolean mayNameCustomers = currentUser.hasAuthority("USER_VIEW");
        return ApiResponse.ok(PagedResponse.of(pageResult.map(l -> toRow(l, people, mayNameCustomers))));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Every account a page of entries mentions, as subject or as actor, in one query. */
    private Map<UUID, User> peopleNamedIn(List<AuditLog> logs) {
        Set<UUID> ids = new HashSet<>();
        for (AuditLog l : logs) {
            if (l.getUserId() != null) ids.add(l.getUserId());
            UUID actor = actorOf(l);
            if (actor != null) ids.add(actor);
        }
        if (ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** The admin recorded as having acted, or null. Most admin-side writes record it as actorId;
     *  AdminUserService and AuthService use a key per action instead (AuditLogRepository
     *  .ADMIN_ACTOR_KEYS), and old rows keep whichever they were written with, so every key is
     *  read. Unparseable values are treated as none rather than failing the page: metadata is
     *  free-form and may have been redacted. */
    private static UUID actorOf(AuditLog l) {
        if (l.getMetadata() == null) return null;
        for (String key : AuditLogRepository.ADMIN_ACTOR_KEYS) {
            Object raw = l.getMetadata().get(key);
            if (raw == null) continue;
            try {
                return UUID.fromString(raw.toString());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** A background job's name, recorded as metadata.actor (e.g. a sweep, or "claude-fix"), so the
     *  page can say an entry was automatic rather than leave "Done by" blank. */
    private static String systemActorOf(AuditLog l) {
        Object raw = l.getMetadata() != null ? l.getMetadata().get("actor") : null;
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private AdminAuditLogRowDto toRow(AuditLog l, Map<UUID, User> people, boolean mayNameCustomers) {
        User subject = nameable(people.get(l.getUserId()), mayNameCustomers);
        UUID actorId = actorOf(l);
        User actor = nameable(people.get(actorId), mayNameCustomers);
        return new AdminAuditLogRowDto(l.getId(), l.getUserId(),
                subject != null ? subject.getFullName() : null, subject != null ? subject.getEmail() : null,
                actorId, actor != null ? actor.getFullName() : null, actor != null ? actor.getEmail() : null,
                systemActorOf(l), l.getAction(), l.getEntityType(), l.getEntityId(), l.getMetadata(), l.getRequestId(),
                l.getCreatedAt(), l.getRedactedAt());
    }

    /** Staff accounts are always named. A customer only for a caller who could already look them up
     *  under Users (USER_VIEW); AUDIT_VIEW alone never revealed who a customer is. */
    private static User nameable(User user, boolean mayNameCustomers) {
        if (user == null) return null;
        return User.SCOPE_ADMIN.equals(user.getAccountScope()) || mayNameCustomers ? user : null;
    }

    private AuditLogDto toDto(com.finora.entity.AuditLog l) {
        return new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt());
    }
}
