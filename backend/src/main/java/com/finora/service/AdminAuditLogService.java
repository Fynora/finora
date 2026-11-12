package com.finora.service;

import com.finora.dto.AdminAuditLogRowDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The admin portal's Audit Log feed, with the people involved named (AdminController
 * .globalAuditLogs). Filtering is AuditLogRepository.search's; this adds who each entry was about
 * and which admin did it, looked up for the whole page in one query.
 */
@Service
public class AdminAuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AdminAuditLogService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * @param q            already LIKE-escaped, or null
     * @param from         inclusive lower bound, or null
     * @param to           exclusive upper bound, or null
     * @param mayNameCustomers whether the caller holds USER_VIEW. Without it a customer's name and
     *                     email stay null: AUDIT_VIEW alone (the FINANCE and OPS presets) never
     *                     revealed who a customer is. Staff accounts are always named.
     */
    @Transactional(readOnly = true)
    public PagedResponse<AdminAuditLogRowDto> globalFeed(String q, Instant from, Instant to, UUID userId, UUID actorId,
                                                         String action, String requestId, Pageable pageable,
                                                         boolean mayNameCustomers) {
        var page = auditLogRepository.search(q, from, to, userId,
                actorId != null ? actorId.toString() : null, blankToNull(action), blankToNull(requestId), pageable);
        Map<UUID, User> people = peopleNamedIn(page.getContent());
        return PagedResponse.of(page.map(l -> toRow(l, people, mayNameCustomers)));
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
    static UUID actorOf(AuditLog l) {
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

    private static AdminAuditLogRowDto toRow(AuditLog l, Map<UUID, User> people, boolean mayNameCustomers) {
        // System entries can have no account; Map.of() (an empty page of names) rejects a null key.
        User subject = nameable(l.getUserId() != null ? people.get(l.getUserId()) : null, mayNameCustomers);
        UUID actorId = actorOf(l);
        User actor = nameable(actorId != null ? people.get(actorId) : null, mayNameCustomers);
        return new AdminAuditLogRowDto(l.getId(), l.getUserId(),
                subject != null ? subject.getFullName() : null, subject != null ? subject.getEmail() : null,
                actorId, actor != null ? actor.getFullName() : null, actor != null ? actor.getEmail() : null,
                systemActorOf(l), l.getAction(), l.getEntityType(), l.getEntityId(), l.getMetadata(),
                l.getRequestId(), l.getCreatedAt(), l.getRedactedAt());
    }

    /** Staff accounts are always named; a customer only for a caller who may look them up. */
    private static User nameable(User user, boolean mayNameCustomers) {
        if (user == null) return null;
        return User.SCOPE_ADMIN.equals(user.getAccountScope()) || mayNameCustomers ? user : null;
    }
}
