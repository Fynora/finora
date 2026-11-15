package com.finora.service.adminusers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Users list query on a large table: 50,000 made-up users with subscriptions, referral grants,
 * accounts, statements and active days. For each filter alone and all of them together, the page
 * query and the count query must never scan accounts, statement_imports, user_activity_days,
 * subscriptions or referral_grants once per user (a single scan feeding a hash join is fine), and
 * must finish within {@link #BOUND_MS}. Measured times are printed for the PR.
 *
 * <p>The seeded rows carry their own email domain and are deleted afterwards: the IT suite shares
 * its tables, and leftover rows would reach other tests' LIMIT queries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminUserListQueryPlanIT extends AbstractIntegrationTest {

    private static final int USERS = 50_000;
    private static final long BOUND_MS = 2_000;
    private static final String DOMAIN = "@ulf-plan-it.example.com";
    private static final Set<String> PER_USER_TABLES =
            Set.of("accounts", "statement_imports", "user_activity_days", "subscriptions", "referral_grants");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminUserListQuery query;
    @Autowired private TransactionTemplate transactions;
    @Autowired private ObjectMapper objectMapper;

    @BeforeAll
    void seed() {
        deleteSeeded();
        jdbc.update("INSERT INTO users (email, password_hash, full_name, created_at, updated_at) "
                + "SELECT 'u' || i || '" + DOMAIN + "', 'x', 'Plan IT ' || i, "
                + "now() - make_interval(mins => i), now() FROM generate_series(1, " + USERS + ") i");
        String seeded = "SELECT id, (regexp_replace(email, '\\D', '', 'g'))::int AS i FROM users WHERE email LIKE '%" + DOMAIN + "'";
        jdbc.update("INSERT INTO subscriptions (user_id, plan_id, status, start_date) "
                + "SELECT s.id, p.id, CASE WHEN s.i % 13 = 0 THEN 'TRIAL' ELSE 'ACTIVE' END, CURRENT_DATE "
                + "FROM (" + seeded + ") s JOIN plans p ON p.code = "
                + "CASE WHEN s.i % 50 = 1 THEN 'PREMIUM' WHEN s.i % 10 = 0 THEN 'PLUS' ELSE 'FREE' END");
        jdbc.update("INSERT INTO referral_grants (user_id, tier, status, activated_at, expires_at) "
                + "SELECT s.id, 'PLUS', 'ACTIVE', now(), now() + interval '30 days' "
                + "FROM (" + seeded + ") s WHERE s.i % 37 = 0");
        jdbc.update("INSERT INTO accounts (user_id, name, account_type, balance, bank_id) "
                + "SELECT s.id, 'Plan IT', 'SAVINGS', 0, (ARRAY['HDFC','ICICI','SBI','AXIS','KOTAK'])[s.i % 5 + 1] "
                + "FROM (" + seeded + ") s");
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content) "
                + "SELECT a.user_id, a.id, 'statement.csv', '\\x00'::bytea "
                + "FROM accounts a JOIN (" + seeded + ") s ON s.id = a.user_id, generate_series(1, s.i % 4) n");
        jdbc.update("INSERT INTO user_activity_days (user_id, activity_date) "
                + "SELECT s.id, CURRENT_DATE - (s.i % 40) - n FROM (" + seeded + ") s, generate_series(0, s.i % 6) n");
        analyze();
    }

    private void analyze() {
        for (String table : List.of("users", "subscriptions", "referral_grants", "accounts",
                "statement_imports", "user_activity_days")) {
            jdbc.execute("ANALYZE " + table);
        }
    }

    @AfterAll
    void deleteSeeded() {
        String seededIds = "SELECT id FROM users WHERE email LIKE '%" + DOMAIN + "'";
        jdbc.update("DELETE FROM statement_imports WHERE user_id IN (" + seededIds + ")");
        jdbc.update("DELETE FROM accounts WHERE user_id IN (" + seededIds + ")");
        jdbc.update("DELETE FROM user_activity_days WHERE user_id IN (" + seededIds + ")");
        jdbc.update("DELETE FROM subscriptions WHERE user_id IN (" + seededIds + ")");
        jdbc.update("DELETE FROM referral_grants WHERE user_id IN (" + seededIds + ")");
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
        // Statistics that still describe 50,000 users would steer the plans of ITs that run later.
        analyze();
    }

    private static Map<String, UserListFilter> filters() {
        LocalDate today = LocalDate.now();
        Map<String, UserListFilter> filters = new LinkedHashMap<>();
        filters.put("plan PLUS", UserListFilter.of(null, null, "PLUS", null, null, null, null, null, null, null));
        filters.put("plan FREE", UserListFilter.of(null, null, "FREE", null, null, null, null, null, null, null));
        filters.put("bank", UserListFilter.of(null, null, null, "SBI", null, null, null, null, null, null));
        filters.put("joined", UserListFilter.of(null, null, null, null, today.minusDays(20), today.minusDays(10), null, null, null, null));
        filters.put("last active from", UserListFilter.of(null, null, null, null, null, null, today.minusDays(5), null, null, null));
        filters.put("last active to", UserListFilter.of(null, null, null, null, null, null, null, today.minusDays(30), null, null));
        filters.put("imports min", UserListFilter.of(null, null, null, null, null, null, null, null, 2, null));
        filters.put("imports none", UserListFilter.of(null, null, null, null, null, null, null, null, null, 0));
        filters.put("all together", UserListFilter.of("plan it", "ACTIVE", "PLUS", "HDFC",
                today.minusDays(40), today, today.minusDays(30), today.minusDays(2), 1, 3));
        return filters;
    }

    @Test
    void everyFilter_neverScansAPerUserTableOncePerUser_andIsFast() throws Exception {
        List<String> report = new ArrayList<>();
        for (var entry : filters().entrySet()) {
            for (boolean count : List.of(false, true)) {
                String plan = transactions.execute(status -> count
                        ? query.explainCountQuery(entry.getValue())
                        : query.explainPageQuery(entry.getValue(), PageRequest.of(0, 20)));
                JsonNode root = objectMapper.readTree(plan).get(0);
                List<String> seqScans = new ArrayList<>();
                collectSeqScans(root.get("Plan"), seqScans);
                double ms = root.get("Execution Time").asDouble();
                String label = entry.getKey() + (count ? " (count)" : " (page)");
                report.add("%-28s %8.1f ms".formatted(label, ms));
                assertThat(seqScans).as("%s: per-user tables scanned once per user%n%s", label, plan).isEmpty();
                assertThat(ms).as("%s execution time", label).isLessThan(BOUND_MS);
            }
        }
        System.out.println("AdminUserListQueryPlanIT on " + USERS + " users:\n  " + String.join("\n  ", report));
    }

    @Test
    void theSeededDataMatchesWhatTheFiltersExpect() {
        // Guards the test itself: each filter has rows to find, so a fast plan is not a plan over nothing.
        long total = transactions.execute(status -> query.findIds(
                UserListFilter.of("plan it", null, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 20)).getTotalElements());
        assertThat(total).isEqualTo(USERS);
        for (var entry : filters().entrySet()) {
            long matched = transactions.execute(status ->
                    query.findIds(entry.getValue(), PageRequest.of(0, 20)).getTotalElements());
            assertThat(matched).as(entry.getKey()).isPositive();
        }
    }

    /**
     * Sequential scans of a per-user table that run once per user: under a correlated SubPlan, or on
     * the inner side of a nested loop. A scan run once (one side of a hash join, possibly split over
     * parallel workers) reads the table once and is the right plan for a filter many users match.
     */
    private static void collectSeqScans(JsonNode node, List<String> out) {
        collectSeqScans(node, false, out);
    }

    private static void collectSeqScans(JsonNode node, boolean repeated, List<String> out) {
        if (node == null) return;
        if (repeated && "Seq Scan".equals(node.path("Node Type").asText())
                && PER_USER_TABLES.contains(node.path("Relation Name").asText())) {
            out.add(node.path("Relation Name").asText());
        }
        boolean nestedLoop = "Nested Loop".equals(node.path("Node Type").asText());
        for (JsonNode child : node.path("Plans")) {
            String relationship = child.path("Parent Relationship").asText();
            boolean childRepeats = repeated || "SubPlan".equals(relationship)
                    || (nestedLoop && "Inner".equals(relationship));
            collectSeqScans(child, childRepeats, out);
        }
    }
}
