package com.finora.service;

import com.finora.dto.AdminDtos.LearningCategoryChange;
import com.finora.dto.AdminDtos.LearningCategorySourceCount;
import com.finora.dto.AdminDtos.LearningCorrectedMerchant;
import com.finora.dto.AdminDtos.LearningEventRow;
import com.finora.dto.AdminDtos.LearningPlatformStatsDto;
import com.finora.dto.AdminDtos.LearningTrendPoint;
import com.finora.dto.AdminDtos.LearningWindow;
import com.finora.dto.PagedResponse;
import com.finora.util.UserZone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Platform-wide Learning Engine figures for the admin console: the aggregate view that has no
 * self-service equivalent (a customer only ever sees their own MerchantLearningService.summary()).
 * To see one account's learning, the admin opens that account (AdminUserLearningController).
 *
 * <p>Customer accounts only: internal accounts (the founder's, testers', {@code
 * users.internal_account}) and admin-scope accounts are left out, the same rule the activation and
 * reading quality pages use, so the founder's own testing does not read as customer behaviour.
 *
 * <h2>What each figure means</h2>
 * <ul>
 *   <li><b>Learned pairs</b>: (merchant, category) pairs with at least one confirmation, now.</li>
 *   <li><b>Confirmations</b>: one per confirmation (MerchantLearningService.confirm), of three
 *       kinds. <em>First time</em>: LEARNED with no previous category, the merchant was new to the
 *       engine. <em>Kept</em>: LEARNED with a previous category, the confirmed category was the
 *       merchant's top one going in. <em>Corrected</em>: CORRECTED, it was not.</li>
 *   <li><b>Correction rate</b> (computed by the page from {@link LearningWindow}): corrected /
 *       (kept + corrected). First-time confirmations are left out because the engine had no answer
 *       to get right or wrong; counting them made the rate fall whenever many new merchants
 *       arrived, however often the engine was overridden. Deleting a category clears it from past
 *       events, so a kept confirmation of a since-deleted category reads as first time. A correction
 *       later undone still counts: the audit trail does not tie an undo to the event it reverted.</li>
 *   <li><b>Months</b> are calendar months in India time ({@link UserZone#DEFAULT}), like the other
 *       admin reports.</li>
 *   <li><b>Category sources</b>: transactions added in the last 30 days (by {@code created_at}),
 *       not deleted and on an account that still exists, by {@code decision_source}. Deleting an
 *       account leaves its transactions' own {@code deleted_at} unset, so the account is checked
 *       here. A category a person set or changed is {@code MANUAL}, whatever picked it first.</li>
 *   <li><b>Most corrected merchants</b>: CORRECTED events by merchant name, grouped across accounts
 *       ignoring case and outer spaces, since every account has its own merchant rows. Category
 *       changes are grouped the same way, since every account has its own category rows.</li>
 * </ul>
 *
 * <p>Every figure is a grouped query in Postgres. The earlier version loaded the whole audit table
 * into memory to bucket the trend by month.
 */
@Service
public class AdminLearningStatsService {

    static final int TOP_LIMIT = 10;
    static final int MAX_PAGE_SIZE = 100;
    private static final Duration WINDOW = Duration.ofDays(30);

    /** The user column {@code %s} belongs to a customer: not internal, not an admin account. */
    private static final String CUSTOMER = """
            EXISTS (SELECT 1 FROM users u WHERE u.id = %s
                     AND NOT u.internal_account AND u.account_scope = 'USER')
            """;

    private static final String PAIRS = """
            WITH per_user AS (
                SELECT l.user_id, COUNT(*) AS pairs
                FROM merchant_category_learning l
                WHERE l.confirmation_count > 0 AND %s
                GROUP BY l.user_id)
            SELECT COALESCE(SUM(pairs), 0) AS pairs, COUNT(*) AS users,
                   COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY pairs), 0) AS median
            FROM per_user
            """.formatted(CUSTOMER.formatted("l.user_id"));

    /** Confirmed with no category to compare against: the merchant was new to the engine. */
    private static final String FIRST_TIME = "a.action = 'LEARNED' AND a.previous_category_id IS NULL";
    /** Confirmed the category the engine already had. */
    private static final String KEPT = "a.action = 'LEARNED' AND a.previous_category_id IS NOT NULL";
    private static final String CORRECTED = "a.action = 'CORRECTED'";

    private static final String ACTIONS = """
            WITH bounds AS (SELECT CAST(? AS timestamptz) AS last_start, CAST(? AS timestamptz) AS previous_start)
            SELECT COUNT(*) FILTER (WHERE a.action IN ('LEARNED', 'CORRECTED')) AS confirmations,
                   COUNT(*) FILTER (WHERE %3$s) AS corrected,
                   COUNT(*) FILTER (WHERE a.action = 'UNDONE') AS undone,
                   COUNT(*) FILTER (WHERE a.action = 'RESET') AS reset,
                   COUNT(*) FILTER (WHERE %1$s AND a.created_at >= b.last_start) AS last_first_time,
                   COUNT(*) FILTER (WHERE %2$s AND a.created_at >= b.last_start) AS last_kept,
                   COUNT(*) FILTER (WHERE %3$s AND a.created_at >= b.last_start) AS last_corrected,
                   COUNT(*) FILTER (WHERE %1$s AND a.created_at >= b.previous_start AND a.created_at < b.last_start) AS previous_first_time,
                   COUNT(*) FILTER (WHERE %2$s AND a.created_at >= b.previous_start AND a.created_at < b.last_start) AS previous_kept,
                   COUNT(*) FILTER (WHERE %3$s AND a.created_at >= b.previous_start AND a.created_at < b.last_start) AS previous_corrected
            FROM merchant_learning_audit a
            CROSS JOIN bounds b
            WHERE %4$s
            """.formatted(FIRST_TIME, KEPT, CORRECTED, CUSTOMER.formatted("a.user_id"));

    private static final String TREND = """
            SELECT to_char(a.created_at AT TIME ZONE '%1$s', 'YYYY-MM') AS month,
                   COUNT(*) FILTER (WHERE %2$s) AS first_time,
                   COUNT(*) FILTER (WHERE %3$s) AS kept,
                   COUNT(*) FILTER (WHERE %4$s) AS corrected,
                   COUNT(*) FILTER (WHERE a.action = 'UNDONE') AS undone,
                   COUNT(*) FILTER (WHERE a.action = 'RESET') AS reset
            FROM merchant_learning_audit a
            WHERE a.action IN ('LEARNED', 'CORRECTED', 'UNDONE', 'RESET') AND %5$s
            GROUP BY 1
            ORDER BY 1
            """.formatted(UserZone.DEFAULT.getId(), FIRST_TIME, KEPT, CORRECTED, CUSTOMER.formatted("a.user_id"));

    private static final String CATEGORY_SOURCES = """
            SELECT t.decision_source, COUNT(*) AS transactions
            FROM transactions t
            JOIN accounts acc ON acc.id = t.account_id AND acc.deleted_at IS NULL
            WHERE t.created_at >= ? AND t.deleted_at IS NULL AND %s
            GROUP BY t.decision_source
            ORDER BY transactions DESC, t.decision_source
            """.formatted(CUSTOMER.formatted("t.user_id"));

    /** Corrections with the keys that group them across accounts, and the names to show. */
    private static final String CORRECTIONS = """
            corrections AS (
                SELECT a.user_id, lower(btrim(m.canonical_name)) AS merchant_key,
                       btrim(m.canonical_name) AS merchant_name,
                       lower(btrim(pc.name)) AS from_key, btrim(pc.name) AS from_name,
                       lower(btrim(nc.name)) AS to_key, btrim(nc.name) AS to_name
                FROM merchant_learning_audit a
                JOIN merchants m ON m.id = a.merchant_id
                LEFT JOIN categories pc ON pc.id = a.previous_category_id
                LEFT JOIN categories nc ON nc.id = a.new_category_id
                WHERE a.action = 'CORRECTED' AND %s)
            """.formatted(CUSTOMER.formatted("a.user_id"));

    private static final String TOP_MERCHANTS = """
            WITH %s,
            totals AS (
                SELECT merchant_key, mode() WITHIN GROUP (ORDER BY merchant_name) AS merchant,
                       COUNT(*) AS corrections, COUNT(DISTINCT user_id) AS users
                FROM corrections
                GROUP BY merchant_key),
            changes AS (
                SELECT merchant_key,
                       mode() WITHIN GROUP (ORDER BY from_name) AS from_name,
                       mode() WITHIN GROUP (ORDER BY to_name) AS to_name,
                       row_number() OVER (PARTITION BY merchant_key
                                          ORDER BY COUNT(*) DESC, from_key, to_key) AS rank
                FROM corrections
                WHERE from_key IS NOT NULL AND to_key IS NOT NULL
                GROUP BY merchant_key, from_key, to_key)
            SELECT t.merchant, t.corrections, t.users, c.from_name, c.to_name
            FROM totals t
            LEFT JOIN changes c ON c.merchant_key = t.merchant_key AND c.rank = 1
            ORDER BY t.corrections DESC, t.users DESC, t.merchant
            LIMIT %d
            """.formatted(CORRECTIONS, TOP_LIMIT);

    private static final String TOP_CHANGES = """
            WITH %s
            SELECT mode() WITHIN GROUP (ORDER BY from_name) AS from_name,
                   mode() WITHIN GROUP (ORDER BY to_name) AS to_name,
                   COUNT(*) AS changes
            FROM corrections
            WHERE from_key IS NOT NULL AND to_key IS NOT NULL
            GROUP BY from_key, to_key
            ORDER BY changes DESC, from_key, to_key
            LIMIT %d
            """.formatted(CORRECTIONS, TOP_LIMIT);

    private static final String EVENTS = """
            SELECT a.id, a.created_at, a.user_id, u.email, a.action, btrim(m.canonical_name) AS merchant,
                   pc.name AS from_name, nc.name AS to_name
            FROM merchant_learning_audit a
            JOIN users u ON u.id = a.user_id AND NOT u.internal_account AND u.account_scope = 'USER'
            JOIN merchants m ON m.id = a.merchant_id
            LEFT JOIN categories pc ON pc.id = a.previous_category_id
            LEFT JOIN categories nc ON nc.id = a.new_category_id
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String EVENT_COUNT = """
            SELECT COUNT(*) FROM merchant_learning_audit a WHERE %s
            """.formatted(CUSTOMER.formatted("a.user_id"));

    private record Pairs(long pairs, long users, double median) {}

    private record Actions(long confirmations, long corrected, long undone, long reset,
                           LearningWindow last30Days, LearningWindow previous30Days) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AdminLearningStatsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    AdminLearningStatsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LearningPlatformStatsDto platformStats() {
        Instant now = clock.instant();
        OffsetDateTime last30Start = now.minus(WINDOW).atOffset(ZoneOffset.UTC);
        OffsetDateTime previous30Start = now.minus(WINDOW.multipliedBy(2)).atOffset(ZoneOffset.UTC);

        Pairs pairs = jdbc.queryForObject(PAIRS,
                (rs, i) -> new Pairs(rs.getLong("pairs"), rs.getLong("users"), rs.getDouble("median")));

        Actions actions = jdbc.queryForObject(ACTIONS, (rs, i) -> new Actions(
                rs.getLong("confirmations"), rs.getLong("corrected"), rs.getLong("undone"), rs.getLong("reset"),
                new LearningWindow(rs.getLong("last_first_time"), rs.getLong("last_kept"), rs.getLong("last_corrected")),
                new LearningWindow(rs.getLong("previous_first_time"), rs.getLong("previous_kept"), rs.getLong("previous_corrected"))),
                last30Start, previous30Start);

        return new LearningPlatformStatsDto(
                pairs.pairs(),
                actions.confirmations(),
                actions.corrected(),
                actions.reset(),
                actions.undone(),
                pairs.users(),
                pairs.median(),
                actions.last30Days(),
                actions.previous30Days(),
                trend(),
                jdbc.query(CATEGORY_SOURCES, (rs, i) -> new LearningCategorySourceCount(
                        rs.getString("decision_source"), rs.getLong("transactions")), last30Start),
                jdbc.query(TOP_MERCHANTS, (rs, i) -> new LearningCorrectedMerchant(
                        rs.getString("merchant"), rs.getLong("corrections"), rs.getLong("users"),
                        rs.getString("from_name"), rs.getString("to_name"))),
                jdbc.query(TOP_CHANGES, (rs, i) -> new LearningCategoryChange(
                        rs.getString("from_name"), rs.getString("to_name"), rs.getLong("changes"))));
    }

    /** Newest first. page is zero-based; size is clamped to 1..{@link #MAX_PAGE_SIZE}. */
    @Transactional(readOnly = true)
    public PagedResponse<LearningEventRow> recentEvents(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Long total = jdbc.queryForObject(EVENT_COUNT, Long.class);
        long totalElements = total == null ? 0 : total;
        List<LearningEventRow> rows = jdbc.query(EVENTS, (rs, i) -> new LearningEventRow(
                rs.getObject("id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("user_id", UUID.class),
                rs.getString("email"),
                rs.getString("action"),
                rs.getString("merchant"),
                rs.getString("from_name"),
                rs.getString("to_name")), safeSize, (long) safePage * safeSize);
        int totalPages = (int) ((totalElements + safeSize - 1) / safeSize);
        return new PagedResponse<>(rows, safePage, safeSize, totalElements, totalPages);
    }

    /** Every month from the first to the last with an event, quiet months as zeros, so a chart
     *  does not jump over a month with no activity. */
    private List<LearningTrendPoint> trend() {
        TreeMap<YearMonth, LearningTrendPoint> byMonth = new TreeMap<>();
        jdbc.query(TREND, rs -> {
            String month = rs.getString("month");
            byMonth.put(YearMonth.parse(month), new LearningTrendPoint(month, rs.getLong("first_time"), rs.getLong("kept"),
                    rs.getLong("corrected"), rs.getLong("undone"), rs.getLong("reset")));
        });
        if (byMonth.isEmpty()) return List.of();
        List<LearningTrendPoint> points = new ArrayList<>();
        for (YearMonth m = byMonth.firstKey(); !m.isAfter(byMonth.lastKey()); m = m.plusMonths(1)) {
            LearningTrendPoint point = byMonth.get(m);
            points.add(point != null ? point : new LearningTrendPoint(m.toString(), 0, 0, 0, 0, 0));
        }
        return points;
    }
}
