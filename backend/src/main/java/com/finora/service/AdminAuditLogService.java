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
        var page = auditLogRepository.search(q, from, to, userId, userId != null ? userId.toString() : null,
                actorId != null ? actorId.toString() : null, actorId, blankToNull(action), blankToNull(requestId),
                AdminUserDataReadAuditInterceptor.ACTION, pageable);
        Map<UUID, User> people = peopleNamedIn(page.getContent());
        return PagedResponse.of(page.map(l -> toRow(l, people, mayNameCustomers)));
    }

    /**
     * One account's history for the user page (AdminController.auditLogsForUser): what
     * AuditLogRepository.findForAdminUserHistory finds. The details are shown raw, as before, except
     * that a customer's identity in them (CUSTOMER_IDENTITY_KEYS) is hidden from a caller without
     * USER_VIEW -- the same rule as the feed, which this endpoint used to sidestep: it needs only
     * AUDIT_VIEW, and the feed shows every account's id.
     */
    @Transactional(readOnly = true)
    public List<com.finora.dto.AuditLogDto> userHistory(UUID userId, boolean mayNameCustomers) {
        boolean hideCustomer = !mayNameCustomers && userRepository.findById(userId)
                .map(u -> !User.SCOPE_ADMIN.equals(u.getAccountScope())).orElse(true);
        return auditLogRepository.findForAdminUserHistory(userId, userId.toString()).stream()
                .map(l -> new com.finora.dto.AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                        l.getEntityId(), hideCustomer ? withoutCustomerIdentity(l.getMetadata()) : l.getMetadata(),
                        l.getRequestId(), l.getCreatedAt()))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Every account a page of entries mentions -- the row's account, the admin named in its
     *  details, the customer it names as subjectUserId, and the customer whose data a read-audit
     *  row says was opened -- in one query. */
    private Map<UUID, User> peopleNamedIn(List<AuditLog> logs) {
        Set<UUID> ids = new HashSet<>();
        for (AuditLog l : logs) {
            if (l.getUserId() != null) ids.add(l.getUserId());
            UUID actor = actorOf(l);
            if (actor != null) ids.add(actor);
            UUID subject = subjectUserIdOf(l);
            if (subject != null) ids.add(subject);
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
     * Whether this row is its staff account's own act on something else. 57 admin-side writes
     * (break-test of 2026-11-14) pass the acting admin as the row's account: about 35 name no actor
     * key at all (layouts, rules, banks, roles, settings, offers, campaigns, release markers, the
     * read audit), the rest also stamp {@code actorId} with that same admin (trust review, held
     * imports, blanked copies). Read so here, not rewritten, so old rows read right too. All of:
     * <ul>
     *   <li>the account is a staff member;</li>
     *   <li>its entity is not that account itself -- an email sent to them, their own or a failed
     *       sign-in on their account happened TO them;</li>
     *   <li>no job is named ({@code metadata.actor}, any value);</li>
     *   <li>no actor key, or {@code actorId} is the account itself.</li>
     * </ul>
     * AuditLogRepository.OWN_ACT is the same test in SQL; keep the two in step.
     */
    private static boolean isOwnAct(AuditLog l, User account) {
        UUID accountId = l.getUserId();
        if (accountId == null || account == null || !User.SCOPE_ADMIN.equals(account.getAccountScope())) return false;
        if (accountId.equals(l.getEntityId())) return false;
        Map<String, Object> md = l.getMetadata();
        if (md != null && md.get("actor") != null) return false;
        if (!hasAnyActorKey(l)) return true;
        Object actorIdValue = md.get("actorId");
        return actorIdValue != null && accountId.toString().equals(actorIdValue.toString());
    }

    /** True when any actor key is present at all (even one that cannot be parsed). */
    private static boolean hasAnyActorKey(AuditLog l) {
        if (l.getMetadata() == null) return false;
        for (String key : AuditLogRepository.ADMIN_ACTOR_KEYS) {
            if (l.getMetadata().get(key) != null) return true;
        }
        return false;
    }

    /** The customer a row is about when the writing service recorded them in its details rather
     *  than as the account: trust review, held imports and blanked copies put the acting admin (or
     *  no one, for the Claude fix job) in the account slot and the customer here. Null if absent or
     *  unreadable. */
    static UUID subjectUserIdOf(AuditLog l) {
        Object raw = l.getMetadata() != null ? l.getMetadata().get("subjectUserId") : null;
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        if (isOwnAct(l, account)) {
            // The staff member in the account slot did this, to something else: they are who acted,
            // and the row is about its entity (a held statement, a rule...) -- or, for a read audit,
            // the customer whose data was opened.
            actorId = accountId;
            subjectId = isDataView(l) ? l.getEntityId() : null;
        }
        // A customer named in the details is who the row is about, whoever is in the account slot.
        UUID namedSubject = subjectUserIdOf(l);
        if (namedSubject != null) subjectId = namedSubject;
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
