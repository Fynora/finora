package com.finora.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.security.AdminUserDataReadAuditInterceptor;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Audit Log's filters and the user page's history can be answered from indexes (V322), not by
 * reading the whole audit table. On a 1,000,000-row local table that was 430-600 ms a request
 * before, 30 ms or less after (2026-11-14).
 *
 * <p>A table this small is always cheaper to scan, so each test turns sequential scans off for its
 * transaction: Postgres then uses an index wherever one can answer the query, and still scans when
 * none can. Counting the table's sequential scans across the real repository call (Hibernate's own
 * SQL, its page count included) therefore fails when some way a filter matches a row has no usable
 * index: a missing index, an index expression that no longer matches the query's
 * {@code jsonb_extract_path_text(metadata, '<key>')}, or a new actor key without an index. Checked
 * 2026-11-14 by dropping the subjectUserId, entity_id and changedBy indexes in turn: each failed
 * the tests that use it.
 */
class AuditLogFilterIndexIT extends AbstractIntegrationTest {

    @Autowired private AuditLogRepository auditLogs;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private UUID customer;
    private UUID admin;

    @BeforeEach
    void rows() {
        customer = user("USER");
        admin = user("ADMIN");
        log(customer, "USER_LOGIN", customer, Map.of());
        log(admin, "TRUST_REVIEW_APPROVED", UUID.randomUUID(),
                Map.of("actorId", admin.toString(), "subjectUserId", customer.toString()));
        log(admin, AdminUserDataReadAuditInterceptor.ACTION, customer, Map.of());
    }

    @Test
    @Transactional
    void theAccountFilter_usesIndexes() {
        long scans = withoutSeqScans(() -> auditLogs.search(null, null, null, customer, customer.toString(),
                null, null, null, null, AdminUserDataReadAuditInterceptor.ACTION, PageRequest.of(0, 25)));
        assertThat(scans).as("sequential scans of audit_logs").isZero();
    }

    @Test
    @Transactional
    void theAdminFilter_usesIndexes() {
        long scans = withoutSeqScans(() -> auditLogs.search(null, null, null, null, null,
                admin.toString(), admin, null, null, AdminUserDataReadAuditInterceptor.ACTION, PageRequest.of(0, 25)));
        assertThat(scans).as("sequential scans of audit_logs").isZero();
    }

    @Test
    @Transactional
    void bothFilters_useIndexes() {
        long scans = withoutSeqScans(() -> auditLogs.search(null, null, null, customer, customer.toString(),
                admin.toString(), admin, null, null, AdminUserDataReadAuditInterceptor.ACTION, PageRequest.of(0, 25)));
        assertThat(scans).as("sequential scans of audit_logs").isZero();
    }

    @Test
    @Transactional
    void theUserPageHistory_usesIndexes() {
        long scans = withoutSeqScans(() -> auditLogs.findForAdminUserHistory(customer, customer.toString()));
        assertThat(scans).as("sequential scans of audit_logs").isZero();
    }

    @Test
    @Transactional
    void aTransactionsHistory_usesTheEntityIndex() {
        long scans = withoutSeqScans(() -> auditLogs.findByEntityIdOrderByCreatedAtAsc(customer));
        assertThat(scans).as("sequential scans of audit_logs").isZero();
    }

    @Test
    @Transactional
    void theCountIsZeroOnlyBecauseOfIndexes_aSeqScanIsCounted() {
        // The instrument itself: a query no index can answer must register, or the tests above prove nothing.
        long scans = withoutSeqScans(() -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE lower(action) LIKE '%login%'", Long.class));
        assertThat(scans).isPositive();
    }

    @Test
    @Transactional
    void theFiltersStillFindTheRows() {
        assertThat(auditLogs.search(null, null, null, customer, customer.toString(), null, null, null, null,
                AdminUserDataReadAuditInterceptor.ACTION, PageRequest.of(0, 25)).getContent())
                .extracting(AuditLog::getAction)
                .containsExactlyInAnyOrder("USER_LOGIN", "TRUST_REVIEW_APPROVED", AdminUserDataReadAuditInterceptor.ACTION);
        assertThat(auditLogs.search(null, null, null, null, null, admin.toString(), admin, null, null,
                AdminUserDataReadAuditInterceptor.ACTION, PageRequest.of(0, 25)).getContent())
                .extracting(AuditLog::getAction)
                .containsExactlyInAnyOrder("TRUST_REVIEW_APPROVED", AdminUserDataReadAuditInterceptor.ACTION);
    }

    /** Sequential scans of audit_logs made by {@code call}, in this transaction, with them disabled. */
    private long withoutSeqScans(Runnable call) {
        jdbc.execute("SET LOCAL enable_seqscan = off");
        long before = seqScans();
        call.run();
        return seqScans() - before;
    }

    private long seqScans() {
        Long n = jdbc.queryForObject(
                "SELECT coalesce(sum(seq_scan), 0) FROM pg_stat_xact_user_tables WHERE relname = 'audit_logs'", Long.class);
        return n == null ? 0 : n;
    }

    private UUID user(String scope) {
        User u = new User();
        u.setEmail("audit-index-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("x");
        u.setFullName("Audit Index IT");
        u.setAccountScope(scope);
        return users.save(u).getId();
    }

    private void log(UUID userId, String action, UUID entityId, Map<String, Object> metadata) {
        AuditLog l = new AuditLog();
        l.setUserId(userId);
        l.setAction(action);
        l.setEntityType("Test");
        l.setEntityId(entityId);
        l.setMetadata(new java.util.HashMap<>(metadata));
        auditLogs.save(l);
    }
}
