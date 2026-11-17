package com.finora.service;

import com.finora.dto.AdminDtos.LearningCategoryChange;
import com.finora.dto.AdminDtos.LearningCategorySourceCount;
import com.finora.dto.AdminDtos.LearningCorrectedMerchant;
import com.finora.dto.AdminDtos.LearningEventRow;
import com.finora.dto.AdminDtos.LearningPlatformStatsDto;
import com.finora.dto.AdminDtos.LearningTrendPoint;
import com.finora.dto.AdminDtos.LearningWindow;
import com.finora.dto.PagedResponse;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li><b>LEARNED / CORRECTED</b>: one per confirmation (MerchantLearningService.confirm). CORRECTED
 *       when the confirmed category was not the merchant's top category going in, so the engine's
 *       guess for that merchant was overridden. Confirmations = LEARNED + CORRECTED.</li>
 *   <li><b>Correction rate</b> (computed by the page from {@link LearningWindow}): corrected /
 *       (learned + corrected) in the last 30 days, against the 30 days before.</li>
 *   <li><b>Category sources</b>: transactions added in the last 30 days (by {@code created_at},
 *       not deleted), by {@code decision_source}. A category a person set or changed is
 *       {@code MANUAL}, whatever picked it first.</li>
 *   <li><b>Most corrected merchants</b>: CORRECTED events by merchant name, grouped across accounts
 *       ignoring case and outer spaces, since every account has its own merchant rows.</li>
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

    private static final String ACTIONS = """
            SELECT a.action,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE a.created_at >= ?) AS last30,
                   COUNT(*) FILTER (WHERE a.created_at >= ? AND a.created_at < ?) AS previous30
            FROM merchant_learning_audit a
            WHERE %s
            GROUP BY a.action
            """.formatted(CUSTOMER.formatted("a.user_id"));

    private static final String TREND = """
            SELECT to_char(date_trunc('month', a.created_at AT TIME ZONE 'UTC'), 'YYYY-MM') AS month,
                   COUNT(*) FILTER (WHERE a.action = 'LEARNED') AS learned,
                   COUNT(*) FILTER (WHERE a.action = 'CORRECTED') AS corrected,
                   COUNT(*) FILTER (WHERE a.action = 'UNDONE') AS undone,
                   COUNT(*) FILTER (WHERE a.action = 'RESET') AS reset
            FROM merchant_learning_audit a
            WHERE a.action IN ('LEARNED', 'CORRECTED', 'UNDONE', 'RESET') AND %s
            GROUP BY 1
            ORDER BY 1
            """.formatted(CUSTOMER.formatted("a.user_id"));

    private static final String CATEGORY_SOURCES = """
            SELECT t.decision_source, COUNT(*) AS transactions
            FROM transactions t
            WHERE t.created_at >= ? AND t.deleted_at IS NULL AND %s
            GROUP BY t.decision_source
            ORDER BY transactions DESC, t.decision_source
            """.formatted(CUSTOMER.formatted("t.user_id"));

    /** Corrections with their merchant key, display name and category names. */
    private static final String CORRECTIONS = """
            corrections AS (
                SELECT a.user_id, lower(btrim(m.canonical_name)) AS merchant_key,
                       btrim(m.canonical_name) AS merchant_name,
                       pc.name AS from_name, nc.name AS to_name
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
                SELECT merchant_key, from_name, to_name,
                       row_number() OVER (PARTITION BY merchant_key
                                          ORDER BY COUNT(*) DESC, from_name, to_name) AS rank
                FROM corrections
                WHERE from_name IS NOT NULL AND to_name IS NOT NULL
                GROUP BY merchant_key, from_name, to_name)
            SELECT t.merchant, t.corrections, t.users, c.from_name, c.to_name
            FROM totals t
            LEFT JOIN changes c ON c.merchant_key = t.merchant_key AND c.rank = 1
            ORDER BY t.corrections DESC, t.users DESC, t.merchant
            LIMIT %d
            """.formatted(CORRECTIONS, TOP_LIMIT);

    private static final String TOP_CHANGES = """
            WITH %s
            SELECT from_name, to_name, COUNT(*) AS changes
            FROM corrections
            WHERE from_name IS NOT NULL AND to_name IS NOT NULL
            GROUP BY from_name, to_name
            ORDER BY changes DESC, from_name, to_name
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

        Map<String, long[]> actions = new HashMap<>(); // action -> [total, last30, previous30]
        jdbc.query(ACTIONS, rs -> {
            actions.put(rs.getString("action"),
                    new long[]{rs.getLong("total"), rs.getLong("last30"), rs.getLong("previous30")});
        }, last30Start, previous30Start, last30Start);
        long[] none = new long[3];
        long[] learned = actions.getOrDefault("LEARNED", none);
        long[] corrected = actions.getOrDefault("CORRECTED", none);

        return new LearningPlatformStatsDto(
                pairs.pairs(),
                learned[0] + corrected[0],
                corrected[0],
                actions.getOrDefault("RESET", none)[0],
                actions.getOrDefault("UNDONE", none)[0],
                pairs.users(),
                pairs.median(),
                new LearningWindow(learned[1], corrected[1]),
                new LearningWindow(learned[2], corrected[2]),
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
            byMonth.put(YearMonth.parse(month), new LearningTrendPoint(month, rs.getLong("learned"),
                    rs.getLong("corrected"), rs.getLong("undone"), rs.getLong("reset")));
        });
        if (byMonth.isEmpty()) return List.of();
        List<LearningTrendPoint> points = new ArrayList<>();
        for (YearMonth m = byMonth.firstKey(); !m.isAfter(byMonth.lastKey()); m = m.plusMonths(1)) {
            LearningTrendPoint point = byMonth.get(m);
            points.add(point != null ? point : new LearningTrendPoint(m.toString(), 0, 0, 0, 0));
        }
        return points;
    }
}
