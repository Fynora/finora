package com.finora.repository;

import com.finora.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** Every metadata key an admin-side audit write records the acting admin's id under. Most use
     *  actorId; AdminUserService (suspend, reactivate, suspension reason, profile edit, internal
     *  flag, phone reset) and AuthService (admin-created user) each name theirs after the action.
     *  The Audit Log reads all of them, so its "done by" and admin filter cover old rows too. */
    List<String> ADMIN_ACTOR_KEYS = List.of(
            "actorId", "suspendedBy", "reactivatedBy", "recordedBy", "updatedBy", "setBy", "createdBy", "changedBy");

    /** Every event recorded against one entity, oldest first -- e.g. proving in an integration
     *  test that a real audit row landed with the expected action and metadata, not just that a
     *  mock was called. */
    List<AuditLog> findByEntityIdOrderByCreatedAtAsc(UUID entityId);

    // Backs the admin portal's global audit feed (AdminController.globalAuditLogs) -- unlike
    // findByUserIdOrderByCreatedAtDesc below, this is genuinely unbounded across every user on
    // the platform, so it's paginated from the start rather than fetched in full. Superseded by
    // search() below as of Admin Portal Phase 5 (the Activity Feed's FilterBar needs q/date-range
    // filtering search() alone provides), kept here since it's a simpler unconditional query a
    // future caller that genuinely wants "just give me everything, no filters" might still prefer.
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Admin Portal Phase 5 (Shared Filtering Framework, Activity Feed's FilterBar) -- backs
     * AdminController.globalAuditLogs' q/dateFrom/dateTo filters, all optional (null skips that
     * condition), same convention UserRepository.search/TransactionRepository.search already
     * establish. q matches against both action and entityType (case-insensitive) -- the two
     * fields the Activity Feed timeline already displays per row, so "search" filtering on what's
     * visibly shown is what an admin scanning the timeline actually expects. Sort direction lives
     * in the Pageable's own Sort (set by the controller), not a separate parameter here.
     *
     * <p>Bug fix: the two date bounds were written as a bare {@code :dateFrom IS NULL}, and that
     * made this entire endpoint return 500 rather than filtering anything:
     *
     * <pre>ERROR: could not determine data type of parameter $4  (SQLState 42P18)</pre>
     *
     * <p>Hibernate emits a named parameter used twice as two separate JDBC placeholders. The
     * second gets its type from {@code a.createdAt >= ?}, but the first appears only as
     * {@code ? IS NULL}, which tells PostgreSQL nothing about what type it is, so the driver
     * refuses to prepare the statement. The {@code CAST} gives it one. Note the {@code :q} branch
     * above was already written this way, for exactly this reason; the date bounds were simply
     * missed.
     *
     * <p>This was not an edge case. Both bounds are null on an unfiltered request, which is the
     * default state of the admin portal's Activity Feed, so the page 500'd on open. It went
     * unnoticed because the four GlobalAuditLogIT tests covering it had never executed: {@code *IT}
     * did not match surefire's default includes (see pom.xml).
     *
     * <p>Correction (2026-09-19): this used to say {@code TransactionRepository.search} was NOT
     * affected, "verified by TransactionRepositoryIT exercising that query with null filters".
     * That verification only ever covered NULL date bounds, which do work. A NON-null
     * {@code dateFrom}/{@code dateTo} reached the same {@code ? IS NULL} placeholder and failed
     * with the same 42P18, so the Transactions screen 500'd on any date filter in production. It
     * is fixed there with the same {@code CAST}; see TransactionSearchDateFilterIT. The lesson is
     * that a null-only test cannot clear a query of this shape -- the non-null value is the case
     * that fails.
     *
     * <p>userId/actorId/action/requestId (Audit Log drill-down, 2026-11-11) are exact matches, so
     * an admin can follow one account, one admin, one kind of event or one request. The acting
     * admin lives in {@code metadata} under one of {@link #ADMIN_ACTOR_KEYS}, read with
     * jsonb_extract_path_text; it is not indexed, which is acceptable for an admin-only page. Keep
     * the key list in this query and the constant in step.
     *
     * <p>Many admin-side writes instead pass the acting admin as the row's account and name no
     * actor (break-test 2026-11-14: 56 calls in 18 files -- trust review, layouts, rules, banks,
     * roles, settings, offers, campaigns, release markers, the read audit). A row whose account is
     * a staff member and whose details name no admin and no job ({@code metadata.actor}) is that
     * staff member's own act, unless its entity is that account itself (an email sent to them,
     * their own or a failed sign-in) -- AdminAuditLogService.toRow reads it so, and these filters match it
     * the same way: under {@code actorUuid} (the same id as {@code actorId}), and not under its
     * account. A read-audit row ({@code dataViewAction}) is about the customer whose data was
     * opened, its entity, so the account filter matches it there.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:q IS NULL
               OR LOWER(a.action) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\'
               OR LOWER(a.entityType) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\')
          AND (CAST(:dateFrom AS timestamp) IS NULL OR a.createdAt >= :dateFrom)
          AND (CAST(:dateTo AS timestamp) IS NULL OR a.createdAt < :dateTo)
          AND (CAST(:userId AS uuid) IS NULL
               OR (a.userId = :userId
                   AND NOT (EXISTS (SELECT u.id FROM User u WHERE u.id = a.userId AND u.accountScope = 'ADMIN')
                            AND (a.entityId IS NULL OR a.entityId <> a.userId)
                            AND function('jsonb_extract_path_text', a.metadata, 'actorId') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'suspendedBy') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'reactivatedBy') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'recordedBy') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'updatedBy') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'setBy') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'createdBy') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'changedBy') IS NULL
                            AND function('jsonb_extract_path_text', a.metadata, 'actor') IS NULL))
               OR (a.action = :dataViewAction AND a.entityId = :userId
                   AND EXISTS (SELECT u.id FROM User u WHERE u.id = a.userId AND u.accountScope = 'ADMIN')
                   AND function('jsonb_extract_path_text', a.metadata, 'actorId') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'actor') IS NULL))
          AND (CAST(:actorId AS string) IS NULL
               OR function('jsonb_extract_path_text', a.metadata, 'actorId') = CAST(:actorId AS string)
               OR function('jsonb_extract_path_text', a.metadata, 'suspendedBy') = CAST(:actorId AS string)
               OR function('jsonb_extract_path_text', a.metadata, 'reactivatedBy') = CAST(:actorId AS string)
               OR function('jsonb_extract_path_text', a.metadata, 'recordedBy') = CAST(:actorId AS string)
               OR function('jsonb_extract_path_text', a.metadata, 'updatedBy') = CAST(:actorId AS string)
               OR function('jsonb_extract_path_text', a.metadata, 'setBy') = CAST(:actorId AS string)
               OR function('jsonb_extract_path_text', a.metadata, 'createdBy') = CAST(:actorId AS string)
               OR function('jsonb_extract_path_text', a.metadata, 'changedBy') = CAST(:actorId AS string)
               OR (a.userId = :actorUuid
                   AND EXISTS (SELECT u.id FROM User u WHERE u.id = a.userId AND u.accountScope = 'ADMIN')
                   AND (a.entityId IS NULL OR a.entityId <> a.userId)
                   AND function('jsonb_extract_path_text', a.metadata, 'actorId') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'suspendedBy') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'reactivatedBy') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'recordedBy') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'updatedBy') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'setBy') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'createdBy') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'changedBy') IS NULL
                   AND function('jsonb_extract_path_text', a.metadata, 'actor') IS NULL))
          AND (CAST(:action AS string) IS NULL OR a.action = :action)
          AND (CAST(:requestId AS string) IS NULL OR a.requestId = :requestId)
        """)
    Page<AuditLog> search(@Param("q") String q, @Param("dateFrom") Instant dateFrom,
                           @Param("dateTo") Instant dateTo, @Param("userId") UUID userId,
                           @Param("actorId") String actorId, @Param("actorUuid") UUID actorUuid,
                           @Param("action") String action, @Param("requestId") String requestId,
                           @Param("dataViewAction") String dataViewAction, Pageable pageable);
    // Full history, unpaged -- backs the self-service Activity Timeline page (ActivityController)
    // and the existing admin-gated per-user audit view (AdminController). Same tradeoff both
    // already accept: audit_logs grows unbounded over a user's lifetime, but this is a review
    // surface, not a hot path, so a real Pageable contract isn't warranted until that's shown to
    // be a problem -- see the Dashboard's own doc comment for why ITS feed uses the Top5 query
    // below instead of this one.
    List<AuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId);

    // The Workspace Dashboard's "recent activity" tile only ever needs the latest handful --
    // Spring Data's derived Top-N query keyword pushes that limit down to the database rather
    // than fetching this user's entire audit history just to take(5) in memory.
    List<AuditLog> findTop5ByUserIdOrderByCreatedAtDesc(UUID userId);

    // Self-service login history (Phase 2 audit hardening / user-security-center-proposal.md
    // §3.1) -- filtered to just the login-family actions (see LoginHistoryController), same
    // Top-N-pushed-to-the-database reasoning as findTop5ByUserIdOrderByCreatedAtDesc above:
    // this is a user reviewing recent activity, not a bulk export, so an unbounded list isn't
    // warranted the way it is for the admin-gated findByUserIdOrderByCreatedAtDesc.
    List<AuditLog> findTop50ByUserIdAndActionInOrderByCreatedAtDesc(UUID userId, List<String> actions);

    /**
     * How many times one action was recorded for a user after a point in time. {@code
     * AccountPurgeSweepService} counts {@code ACCOUNT_PURGE_FAILED} rows since the account's
     * {@code deletionRequestedAt} to decide when a purge that keeps failing has gone on long enough
     * to tell an admin -- one count per failed sweep, on a stuck account only, so a derived
     * {@code COUNT(*)} is all this needs.
     */
    long countByUserIdAndActionAndCreatedAtAfter(UUID userId, String action, Instant after);

    /**
     * Admin Portal Phase 4 (EntityDrawer reference implementation, Banks page's Audit tab) --
     * BANK_CREATED/BANK_UPDATED/BANK_DELETED all record entityId as null (Bank.id is a String
     * natural key, e.g. "hdfc", not a UUID -- see this entity's entityId column type), carrying
     * the real bank id inside metadata's "bankId" key instead (see BankManagementService
     * .createCustom/updateCustom/deleteCustom). Native query, not JPQL: Hibernate's JPQL has no
     * operator for Postgres's jsonb ->> text-extraction this needs -- AccountRepository
     * .findByUserId already establishes @Query(nativeQuery = true) as an accepted pattern in this
     * codebase for exactly this "JPQL can't express it" case. A real, honest per-entity audit
     * trail (every field genuinely persisted), not a fabricated one.
     */
    @Query(value = "SELECT * FROM audit_logs WHERE entity_type = 'Bank' AND metadata->>'bankId' = :bankId ORDER BY created_at DESC",
            nativeQuery = true)
    List<AuditLog> findByBankIdInMetadata(@Param("bankId") String bankId);

    /**
     * BH-044's redaction sweep ({@code AuditService.redactExpiredMetadata}) -- the candidate-
     * discovery half only. Ordered oldest-first, same reasoning as {@code ImportSessionRepository
     * .findByExpiresAtBeforeOrderByExpiresAtAsc}: a backlog drains in a stable order across runs
     * rather than being reshuffled run to run. Backed by {@code idx_audit_logs_created_at_unredacted}
     * (V89), a partial index on {@code redacted_at IS NULL} that stays small as more rows get
     * redacted over time.
     *
     * <p>An ordinary derived find query, not the bare {@code deleteBy…} that {@code AuditService}'s
     * class doc warns must never be added here: this only locates candidates, and the actual
     * mutation happens inside {@code AuditService} by loading each entity and calling
     * {@code save(...)}, the same pattern {@code record()} already uses.
     */
    List<AuditLog> findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(Instant cutoff, Pageable limit);

    /**
     * The safety-critical fresh re-check {@code AuditService.redactExpiredMetadata} runs
     * immediately before mutating each candidate -- a live read, not a check of the in-memory
     * object the discovery query above already returned. That distinction matters: {@link
     * #findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc} can be stale by the time
     * execution reaches a given row (most plausibly a second app instance's redaction pass
     * reaching the same row first, since Railway can run more than one instance and the
     * scheduler's {@code fixedDelay} only prevents overlap within one JVM), and an in-memory
     * candidate object can never reflect that -- it was already guaranteed {@code redactedAt IS
     * NULL} at SELECT time and nothing re-fetches it afterward. This method exists specifically
     * to close that gap, mirroring {@code StatementStorageSweepService.sweep}'s own fresh
     * {@code existsBy…} calls right before its irreversible action.
     */
    boolean existsByIdAndRedactedAtIsNull(UUID id);
}
