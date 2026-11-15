package com.finora.service.adminusers;

import com.finora.activation.ReportingDay;
import com.finora.entity.ReferralGrant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin Users list query: one SQL clause per filter that is set, nothing for the rest.
 *
 * <p>Built rather than written as one fixed query with "{@code :x IS NULL OR ...}" for every filter
 * (Sid chose this, 2026-11-15): pgjdbc server-prepares a statement after a few runs and Postgres may
 * then reuse one generic plan for every combination of filters, which is how the audit log's filter
 * test came to measure a sequential scan. Here each combination is its own SQL text. Only bound
 * parameters reach the SQL; the clauses themselves are fixed strings.
 *
 * <p>What each filter means is on {@link UserListFilter}. Plan follows
 * {@code EntitlementService.effectivePlanCodeFor}: the higher of the ACTIVE/TRIAL subscription's
 * plan and an ACTIVE referral grant that has not passed its end date. Imports are statements not
 * deleted, in an account not deleted: deleting an account leaves its statements' rows undeleted.
 */
@Component
public class AdminUserListQuery {

    /** A page row's plan code (null when the user has no plan), latest active day, and import count. */
    public record UserListFacts(String planCode, LocalDate lastActiveDate, long importCount) {
        static final UserListFacts NONE = new UserListFacts(null, null, 0);
    }

    private static final String RANK_OF_PLAN_CODE =
            "CASE %s WHEN 'PREMIUM' THEN 2 WHEN 'PLUS' THEN 1 ELSE 0 END";

    private static final String PLAN_RANK =
            "GREATEST("
            + "COALESCE((SELECT MAX(" + RANK_OF_PLAN_CODE.formatted("p.code") + ") FROM subscriptions s"
            + " JOIN plans p ON p.id = s.plan_id"
            + " WHERE s.user_id = u.id AND s.deleted_at IS NULL AND s.status IN ('ACTIVE', 'TRIAL')), 0), "
            + "COALESCE((SELECT MAX(" + RANK_OF_PLAN_CODE.formatted("g.tier") + ") FROM referral_grants g"
            + " WHERE g.user_id = u.id AND g.status = 'ACTIVE'"
            + " AND (g.expires_at IS NULL OR g.expires_at > :now)), 0))";

    private static final String IMPORT_COUNT =
            "(SELECT COUNT(*) FROM statement_imports si"
            + " JOIN accounts a ON a.id = si.account_id AND a.deleted_at IS NULL"
            + " WHERE si.user_id = u.id AND si.deleted_at IS NULL)";

    @PersistenceContext
    private EntityManager entityManager;

    private final ReportingDay reportingDay;

    public AdminUserListQuery(ReportingDay reportingDay) {
        this.reportingDay = reportingDay;
    }

    /** The page's user ids, newest sign-up first (then id), and the total that match. */
    public Page<UUID> findIds(UserListFilter filter, Pageable pageable) {
        Where where = where(filter);

        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM users u" + where.sql);
        where.params.forEach(count::setParameter);
        long total = ((Number) count.getSingleResult()).longValue();

        Query page = entityManager.createNativeQuery(pageSql(where));
        where.params.forEach(page::setParameter);
        page.setParameter("limit", pageable.getPageSize());
        page.setParameter("offset", pageable.getOffset());
        @SuppressWarnings("unchecked")
        List<UUID> ids = page.getResultList();
        return new PageImpl<>(ids, pageable, total);
    }

    /** Plan, last active day and import count for these users, in four set queries (not per row). */
    public Map<UUID, UserListFacts> factsFor(Collection<UUID> ids) {
        Map<UUID, UserListFacts> facts = new HashMap<>();
        if (ids.isEmpty()) return facts;
        Instant now = reportingDay.now();

        Map<UUID, String> subscriptionPlan = new HashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                "SELECT s.user_id, p.code FROM subscriptions s JOIN plans p ON p.id = s.plan_id"
                + " WHERE s.user_id IN (:ids) AND s.deleted_at IS NULL AND s.status IN ('ACTIVE', 'TRIAL')")
                .setParameter("ids", ids))) {
            subscriptionPlan.merge((UUID) row[0], (String) row[1], AdminUserListQuery::higher);
        }
        Map<UUID, String> grantTier = new HashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                "SELECT g.user_id, g.tier FROM referral_grants g WHERE g.user_id IN (:ids)"
                + " AND g.status = 'ACTIVE' AND (g.expires_at IS NULL OR g.expires_at > :now)")
                .setParameter("ids", ids).setParameter("now", now))) {
            grantTier.merge((UUID) row[0], (String) row[1], AdminUserListQuery::higher);
        }
        Map<UUID, LocalDate> lastActive = new HashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                "SELECT d.user_id, MAX(d.activity_date) FROM user_activity_days d"
                + " WHERE d.user_id IN (:ids) GROUP BY d.user_id")
                .setParameter("ids", ids))) {
            lastActive.put((UUID) row[0], toLocalDate(row[1]));
        }
        Map<UUID, Long> imports = new HashMap<>();
        for (Object[] row : rows(entityManager.createNativeQuery(
                "SELECT si.user_id, COUNT(*) FROM statement_imports si"
                + " JOIN accounts a ON a.id = si.account_id AND a.deleted_at IS NULL"
                + " WHERE si.user_id IN (:ids) AND si.deleted_at IS NULL GROUP BY si.user_id")
                .setParameter("ids", ids))) {
            imports.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        for (UUID id : ids) {
            String plan = effectivePlan(subscriptionPlan.get(id), grantTier.get(id));
            facts.put(id, new UserListFacts(plan, lastActive.get(id), imports.getOrDefault(id, 0L)));
        }
        return facts;
    }

    /** EXPLAIN (ANALYZE) of the page query, for the large-table test. */
    String explainPageQuery(UserListFilter filter, Pageable pageable) {
        Where where = where(filter);
        Query query = entityManager.createNativeQuery("EXPLAIN (ANALYZE, FORMAT JSON) " + pageSql(where));
        where.params.forEach(query::setParameter);
        query.setParameter("limit", pageable.getPageSize());
        query.setParameter("offset", pageable.getOffset());
        return String.valueOf(query.getSingleResult());
    }

    private static String pageSql(Where where) {
        return "SELECT u.id FROM users u" + where.sql
                + " ORDER BY u.created_at DESC, u.id DESC LIMIT :limit OFFSET :offset";
    }

    /** Same rule as EntitlementService: the grant wins only when it ranks strictly higher. */
    static String effectivePlan(String subscriptionPlan, String grantTier) {
        if (grantTier == null) return subscriptionPlan;
        if (subscriptionPlan == null) return grantTier;
        return ReferralGrant.tierRank(grantTier) > ReferralGrant.tierRank(subscriptionPlan)
                ? grantTier : subscriptionPlan;
    }

    private static String higher(String a, String b) {
        return ReferralGrant.tierRank(b) > ReferralGrant.tierRank(a) ? b : a;
    }

    private record Where(String sql, Map<String, Object> params) {}

    private Where where(UserListFilter f) {
        StringBuilder sql = new StringBuilder(" WHERE TRUE");
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.q() != null) {
            sql.append(" AND (LOWER(u.email) LIKE LOWER('%' || :q || '%') ESCAPE '\\'")
               .append(" OR LOWER(u.full_name) LIKE LOWER('%' || :q || '%') ESCAPE '\\'")
               .append(" OR u.phone_number LIKE '%' || :q || '%' ESCAPE '\\')");
            params.put("q", f.q());
        }
        if (f.status() != null) {
            sql.append(" AND u.status = :status");
            params.put("status", f.status());
        }
        if (f.joinedFrom() != null) {
            sql.append(" AND u.created_at >= :joinedFrom");
            params.put("joinedFrom", startOf(f.joinedFrom()));
        }
        if (f.joinedTo() != null) {
            sql.append(" AND u.created_at < :joinedBefore");
            params.put("joinedBefore", startOf(f.joinedTo().plusDays(1)));
        }
        if (f.lastActiveFrom() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM user_activity_days d"
                    + " WHERE d.user_id = u.id AND d.activity_date >= :lastActiveFrom)");
            params.put("lastActiveFrom", f.lastActiveFrom());
        }
        if (f.lastActiveTo() != null) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM user_activity_days d"
                    + " WHERE d.user_id = u.id AND d.activity_date > :lastActiveTo)");
            params.put("lastActiveTo", f.lastActiveTo());
        }
        if (f.bankId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM accounts a"
                    + " WHERE a.user_id = u.id AND a.deleted_at IS NULL AND a.bank_id = :bankId)");
            params.put("bankId", f.bankId());
        }
        if (f.importsMin() != null && f.importsMax() != null) {
            sql.append(" AND ").append(IMPORT_COUNT).append(" BETWEEN :importsMin AND :importsMax");
            params.put("importsMin", f.importsMin().longValue());
            params.put("importsMax", f.importsMax().longValue());
        } else if (f.importsMin() != null) {
            sql.append(" AND ").append(IMPORT_COUNT).append(" >= :importsMin");
            params.put("importsMin", f.importsMin().longValue());
        } else if (f.importsMax() != null) {
            sql.append(" AND ").append(IMPORT_COUNT).append(" <= :importsMax");
            params.put("importsMax", f.importsMax().longValue());
        }
        if (f.plan() != null) {
            sql.append(" AND ").append(PLAN_RANK).append(" = :planRank");
            params.put("planRank", ReferralGrant.tierRank(f.plan()));
            params.put("now", reportingDay.now());
        }
        return new Where(sql.toString(), params);
    }

    private Instant startOf(LocalDate day) {
        return day.atStartOfDay(reportingDay.zone()).toInstant();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        throw new IllegalStateException("Unexpected activity_date type: " + value.getClass());
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }
}
