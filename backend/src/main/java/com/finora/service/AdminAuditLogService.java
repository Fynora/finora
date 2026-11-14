package com.finora.service;

import com.finora.dto.AdminAuditLogRowDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import com.finora.security.AdminUserDataReadAuditInterceptor;
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
                actorId != null ? actorId.toString() : null, actorId, blankToNull(action), blankToNull(requestId),
                AdminUserDataReadAuditInterceptor.ACTION, pageable);
        Map<UUID, User> people = peopleNamedIn(page.getContent());
        return PagedResponse.of(page.map(l -> toRow(l, people, mayNameCustomers)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Every account a page of entries mentions -- the row's account, the admin named in its
     *  details, and the customer whose data a read-audit row says was opened -- in one query. */
    private Map<UUID, User> peopleNamedIn(List<AuditLog> logs) {
        Set<UUID> ids = new HashSet<>();
        for (AuditLog l : logs) {
            if (l.getUserId() != null) ids.add(l.getUserId());
            UUID actor = actorOf(l);
            if (actor != null) ids.add(actor);
            if (isDataView(l) && l.getEntityId() != null) ids.add(l.getEntityId());
        }
        if (ids.isEmpty()) return Map.of();
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** The admin recorded as having acted, or null. Most admin-side writes record it as actorId;
     *  AdminUserService and AuthService use a key per action instead (AuditLogRepository
     *  .ADMIN_ACTOR_KEYS), and old rows keep whichever they were written with, so every key is
     *  read. An unparseable value is skipped, not the end of the search: the next key may hold the
     *  admin, and AuditLogRepository.search matches that key too (break-test 2026-11-14) --
     *  metadata is free-form and may have been redacted. */
    static UUID actorOf(AuditLog l) {
        if (l.getMetadata() == null) return null;
        for (String key : AuditLogRepository.ADMIN_ACTOR_KEYS) {
            Object raw = l.getMetadata().get(key);
            if (raw == null) continue;
            try {
                return UUID.fromString(raw.toString());
            } catch (IllegalArgumentException e) {
                // try the next key
            }
        }
        return null;
    }

    /** "An admin opened this customer's data" (AdminUserDataReadAuditInterceptor): the row's
     *  account is the admin who looked, and the customer is its entity. */
    private static boolean isDataView(AuditLog l) {
        return AdminUserDataReadAuditInterceptor.ACTION.equals(l.getAction());
    }

    /**
     * Whether no admin is named in the details and no job either -- so, when the row's account is
     * a staff member, that staff member is who acted. Many admin-side writes (trust review,
     * layouts, rules, banks, roles, settings, offers, campaigns, release markers, the read audit;
     * 56 calls in 18 files at the break-test of 2026-11-14) pass the acting admin as the row's
     * account and add no actor key. Read here, not rewritten, so old rows read right too. Only
     * when the row's entity is not the account itself -- see toRow.
     */
    private static boolean actsAsItsOwnAccount(AuditLog l) {
        return actorOf(l) == null && systemActorOf(l) == null && !hasAnyActorKey(l);
    }

    /** True when any actor key is present at all (even one that cannot be parsed): such a row
     *  names its admin, or names one that cannot be read, and is never re-attributed. */
    private static boolean hasAnyActorKey(AuditLog l) {
        if (l.getMetadata() == null) return false;
        for (String key : AuditLogRepository.ADMIN_ACTOR_KEYS) {
            if (l.getMetadata().get(key) != null) return true;
        }
        return false;
    }

    /** Details that would say who a customer is, for a caller who may not know (no USER_VIEW). */
    static final Set<String> CUSTOMER_IDENTITY_KEYS = Set.of("googleEmail");

    /** A background job's name, recorded as metadata.actor (e.g. a sweep, or "claude-fix"), so the
     *  page can say an entry was automatic rather than leave "Done by" blank. */
    private static String systemActorOf(AuditLog l) {
        Object raw = l.getMetadata() != null ? l.getMetadata().get("actor") : null;
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static AdminAuditLogRowDto toRow(AuditLog l, Map<UUID, User> people, boolean mayNameCustomers) {
        // System entries can have no account; Map.of() (an empty page of names) rejects a null key.
        UUID accountId = l.getUserId();
        User account = accountId != null ? people.get(accountId) : null;
        UUID actorId = actorOf(l);
        UUID subjectId = accountId;
        if (actorId == null && account != null && User.SCOPE_ADMIN.equals(account.getAccountScope())
                && !accountId.equals(l.getEntityId()) && actsAsItsOwnAccount(l)) {
            // A staff member's account on a row naming no other actor, about something other than
            // that account: they are who did it. The row is about its entity (a held statement, a
            // rule...), or, for a read audit, the customer whose data was opened. A row whose entity
            // IS the account (an email sent to them, their own sign-in, a failed sign-in to their
            // account) happened to them and stays about them.
            actorId = accountId;
            subjectId = isDataView(l) ? l.getEntityId() : null;
        }
        User subjectUser = subjectId != null ? people.get(subjectId) : null;
        User subject = nameable(subjectUser, mayNameCustomers);
        User actor = nameable(actorId != null ? people.get(actorId) : null, mayNameCustomers);
        boolean customerHidden = !mayNameCustomers && subjectUser != null
                && !User.SCOPE_ADMIN.equals(subjectUser.getAccountScope());
        return new AdminAuditLogRowDto(l.getId(), subjectId,
                subject != null ? subject.getFullName() : null, subject != null ? subject.getEmail() : null,
                actorId, actor != null ? actor.getFullName() : null, actor != null ? actor.getEmail() : null,
                systemActorOf(l), l.getAction(), l.getEntityType(), l.getEntityId(),
                customerHidden ? withoutCustomerIdentity(l.getMetadata()) : l.getMetadata(),
                l.getRequestId(), l.getCreatedAt(), l.getRedactedAt());
    }

    /** The details with anything that says who the customer is replaced (CUSTOMER_IDENTITY_KEYS):
     *  naming the customer is hidden from this caller, so the details must not name them either. */
    private static Map<String, Object> withoutCustomerIdentity(Map<String, Object> metadata) {
        if (metadata == null || CUSTOMER_IDENTITY_KEYS.stream().noneMatch(metadata::containsKey)) return metadata;
        Map<String, Object> copy = new java.util.LinkedHashMap<>(metadata);
        for (String key : CUSTOMER_IDENTITY_KEYS) {
            if (copy.containsKey(key)) copy.put(key, "hidden (needs USER_VIEW)");
        }
        return copy;
    }

    /** Staff accounts are always named; a customer only for a caller who may look them up. */
    private static User nameable(User user, boolean mayNameCustomers) {
        if (user == null) return null;
        return User.SCOPE_ADMIN.equals(user.getAccountScope()) || mayNameCustomers ? user : null;
    }
}
