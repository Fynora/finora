package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform-wide Learning Engine figures (AdminLearningStatsService) at the HTTP layer: the real
 * MERCHANT_MANAGE gate and the real queries against Postgres. The tables are shared with every
 * other IT, so totals are checked as changes from a reading taken before the test's own rows, and
 * every merchant and category name is unique to the test. Internal accounts are written alongside
 * customers in each case, to prove they are left out.
 */
class AdminLearningStatsControllerIT extends AbstractIntegrationTest {

    private static final String STATS = "/api/v1/admin/learning/stats";
    private static final String EVENTS = "/api/v1/admin/learning/events";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    // ---- fixtures ------------------------------------------------------------------------------

    private User admin() {
        User user = new User();
        user.setEmail("learning-engine-it-admin-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Engine IT Admin");
        user.setRole("ADMIN");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private User customer(boolean internal) {
        User user = new User();
        user.setEmail("learning-engine-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Engine IT Customer");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user.setInternalAccount(internal);
        return userRepository.save(user);
    }

    private UUID merchant(User owner, String name) {
        Merchant merchant = new Merchant();
        merchant.setUserId(owner.getId());
        merchant.setCanonicalName(name);
        return merchantRepository.save(merchant).getId();
    }

    private UUID category(User owner, String name) {
        Category category = new Category();
        category.setUserId(owner.getId());
        category.setName(name);
        return categoryRepository.save(category).getId();
    }

    private void pair(User owner, UUID merchantId, UUID categoryId, int confirmations) {
        MerchantCategoryLearning pair = new MerchantCategoryLearning();
        pair.setUserId(owner.getId());
        pair.setMerchantId(merchantId);
        pair.setCategoryId(categoryId);
        pair.setConfirmationCount(confirmations);
        pair.setConfidence(100);
        learningRepository.save(pair);
    }

    private void events(int times, User owner, UUID merchantId, String action, UUID from, UUID to, Instant at) {
        for (int i = 0; i < times; i++) {
            jdbc.update("""
                    INSERT INTO merchant_learning_audit (merchant_id, user_id, action, previous_category_id,
                                                         new_category_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, merchantId, owner.getId(), action, from, to, Timestamp.from(at.plusMillis(i)));
        }
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private void transactions(int times, Account account, String decisionSource, Instant createdAt, boolean deleted) {
        for (int i = 0; i < times; i++) {
            jdbc.update("""
                    INSERT INTO transactions (id, user_id, account_id, txn_date, description, merchant, amount, txn_type,
                                              created_at, updated_at, version, reconciliation_status, source,
                                              counterparty_type, decision_source, deleted_at)
                    VALUES (?, ?, ?, DATE '2026-07-01', 'learning engine it row', 'learning engine it', 10.00,
                            'EXPENSE', ?, now(), 1, 'OK', 'CSV_IMPORT', 'UNKNOWN', ?, ?)
                    """, UUID.randomUUID(), account.getUserId(), account.getId(), Timestamp.from(createdAt),
                    decisionSource, deleted ? Timestamp.from(Instant.now()) : null);
        }
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode get(User as, String path) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearerFor(as)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static long sourceCount(JsonNode stats, String source) {
        for (JsonNode row : stats.get("categorySources")) {
            if (row.get("source").asText().equals(source)) return row.get("transactions").asLong();
        }
        return 0;
    }

    private static JsonNode find(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.get(field).asText())) return row;
        }
        return null;
    }

    private static long delta(JsonNode after, JsonNode before, String pointer) {
        return after.at(pointer).asLong() - before.at(pointer).asLong();
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    void aCustomerCannotReadEitherRoute() {
        User user = customer(false);
        for (String path : List.of(STATS, EVENTS)) {
            ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(bearerFor(user)), String.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void totalsAndWindowsCountCustomersOnly() throws Exception {
        User admin = admin();
        JsonNode before = get(admin, STATS);

        User customer = customer(false);
        User internal = customer(true);
        Instant now = Instant.now();
        UUID merchant = merchant(customer, "LE IT totals " + tag);
        UUID food = category(customer, "LE IT Food " + tag);
        UUID groceries = category(customer, "LE IT Groceries " + tag);
        pair(customer, merchant, food, 3);
        pair(customer, merchant, groceries, 1);
        pair(customer, merchant(customer, "LE IT empty " + tag), food, 0); // no confirmation: not a pair
        events(3, customer, merchant, "LEARNED", null, food, now.minus(Duration.ofDays(5)));
        events(1, customer, merchant, "CORRECTED", food, groceries, now.minus(Duration.ofDays(5)));
        events(2, customer, merchant, "CORRECTED", groceries, food, now.minus(Duration.ofDays(40)));
        events(4, customer, merchant, "LEARNED", null, food, now.minus(Duration.ofDays(90)));
        events(1, customer, merchant, "UNDONE", null, null, now.minus(Duration.ofDays(1)));
        events(1, customer, merchant, "RESET", food, null, now.minus(Duration.ofDays(1)));

        UUID internalMerchant = merchant(internal, "LE IT internal " + tag);
        UUID internalCategory = category(internal, "LE IT Food " + tag);
        pair(internal, internalMerchant, internalCategory, 5);
        events(7, internal, internalMerchant, "CORRECTED", internalCategory, internalCategory, now.minus(Duration.ofDays(2)));
        events(2, internal, internalMerchant, "RESET", internalCategory, null, now.minus(Duration.ofDays(2)));

        JsonNode after = get(admin, STATS);

        assertThat(delta(after, before, "/learnedMerchantPairs")).isEqualTo(2);
        assertThat(delta(after, before, "/usersWithLearning")).isEqualTo(1);
        assertThat(delta(after, before, "/totalConfirmations")).isEqualTo(10);
        assertThat(delta(after, before, "/correctedCount")).isEqualTo(3);
        assertThat(delta(after, before, "/undoneCount")).isEqualTo(1);
        assertThat(delta(after, before, "/resetCount")).isEqualTo(1);
        assertThat(delta(after, before, "/last30Days/learned")).isEqualTo(3);
        assertThat(delta(after, before, "/last30Days/corrected")).isEqualTo(1);
        assertThat(delta(after, before, "/previous30Days/learned")).isZero();
        assertThat(delta(after, before, "/previous30Days/corrected")).isEqualTo(2);
    }

    @Test
    void medianPairsPerUserMatchesAnIndependentCount() throws Exception {
        User admin = admin();
        User one = customer(false);
        User two = customer(false);
        UUID category = category(one, "LE IT median " + tag);
        for (int i = 0; i < 5; i++) pair(one, merchant(one, "LE IT median " + tag + " " + i), category, 1);
        pair(two, merchant(two, "LE IT median two " + tag), category(two, "LE IT median " + tag), 2);

        List<Long> perUser = new ArrayList<>(jdbc.queryForList("""
                SELECT COUNT(*) FROM merchant_category_learning l JOIN users u ON u.id = l.user_id
                WHERE l.confirmation_count > 0 AND NOT u.internal_account AND u.account_scope = 'USER'
                GROUP BY l.user_id ORDER BY 1
                """, Long.class));
        int n = perUser.size();
        double expected = n % 2 == 1 ? perUser.get(n / 2) : (perUser.get(n / 2 - 1) + perUser.get(n / 2)) / 2.0;

        JsonNode stats = get(admin, STATS);
        assertThat(stats.get("usersWithLearning").asLong()).isEqualTo(n);
        assertThat(stats.get("medianPairsPerUser").asDouble()).isEqualTo(expected);
    }

    @Test
    void trendFillsQuietMonthsAndCountsEachAction() throws Exception {
        User admin = admin();
        User customer = customer(false);
        User internal = customer(true);
        UUID merchant = merchant(customer, "LE IT trend " + tag);
        UUID category = category(customer, "LE IT trend " + tag);
        // Months no other test writes to, so their counts are exactly this test's.
        events(4, customer, merchant, "LEARNED", null, category, Instant.parse("2001-01-10T00:00:00Z"));
        events(1, customer, merchant, "CORRECTED", category, category, Instant.parse("2001-01-31T23:59:00Z"));
        events(2, customer, merchant, "UNDONE", null, null, Instant.parse("2001-03-01T00:00:00Z"));
        events(1, customer, merchant, "RESET", category, null, Instant.parse("2001-03-15T00:00:00Z"));
        events(1, customer, merchant, "MERGED", null, null, Instant.parse("2000-12-15T00:00:00Z"));
        UUID internalMerchant = merchant(internal, "LE IT trend internal " + tag);
        events(9, internal, internalMerchant, "LEARNED", null, null, Instant.parse("2001-01-12T00:00:00Z"));

        JsonNode trend = get(admin, STATS).get("trend");

        assertThat(find(trend, "month", "2000-12")).as("a month with only a merge has no learning").isNull();
        JsonNode jan = find(trend, "month", "2001-01");
        assertThat(jan).isNotNull();
        assertThat(jan.get("learned").asLong()).isEqualTo(4);
        assertThat(jan.get("corrected").asLong()).isEqualTo(1);
        JsonNode feb = find(trend, "month", "2001-02");
        assertThat(feb).as("quiet month filled").isNotNull();
        assertThat(feb.get("learned").asLong() + feb.get("corrected").asLong()
                + feb.get("undone").asLong() + feb.get("reset").asLong()).isZero();
        JsonNode mar = find(trend, "month", "2001-03");
        assertThat(mar.get("undone").asLong()).isEqualTo(2);
        assertThat(mar.get("reset").asLong()).isEqualTo(1);
        List<String> months = new ArrayList<>();
        trend.forEach(p -> months.add(p.get("month").asText()));
        assertThat(months).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void categorySourcesCountLiveCustomerTransactionsFromTheLast30Days() throws Exception {
        User admin = admin();
        JsonNode before = get(admin, STATS);
        Account customer = account(customer(false));
        Account internal = account(customer(true));
        Instant now = Instant.now();
        transactions(3, customer, "LEARNED_PATTERN", now.minus(Duration.ofDays(2)), false);
        transactions(2, customer, "MANUAL", now.minus(Duration.ofDays(29)), false);
        transactions(1, customer, "LEARNED_PATTERN", now.minus(Duration.ofDays(2)), true);  // deleted
        transactions(4, customer, "LEARNED_PATTERN", now.minus(Duration.ofDays(31)), false); // too old
        transactions(5, internal, "LEARNED_PATTERN", now.minus(Duration.ofDays(2)), false);

        JsonNode after = get(admin, STATS);
        assertThat(sourceCount(after, "LEARNED_PATTERN") - sourceCount(before, "LEARNED_PATTERN")).isEqualTo(3);
        assertThat(sourceCount(after, "MANUAL") - sourceCount(before, "MANUAL")).isEqualTo(2);
    }

    @Test
    void correctedMerchantsGroupNamesAcrossAccountsAndNameTheirUsualChange() throws Exception {
        User admin = admin();
        User one = customer(false);
        User two = customer(false);
        User internal = customer(true);
        String name = "LE IT Corrected " + tag;
        String food = "LE IT Food " + tag;
        String groceries = "LE IT Groceries " + tag;
        String shopping = "LE IT Shopping " + tag;
        Instant at = Instant.now().minus(Duration.ofDays(3));

        UUID m1 = merchant(one, name);
        events(30, one, m1, "CORRECTED", category(one, food), category(one, groceries), at);
        UUID m2 = merchant(two, "  " + name.toLowerCase() + " ");
        UUID twoShopping = category(two, shopping);
        UUID twoGroceries = category(two, groceries);
        events(10, two, m2, "CORRECTED", twoShopping, twoGroceries, at);
        events(5, two, m2, "CORRECTED", null, twoGroceries, at); // from-category deleted since
        events(3, two, m2, "LEARNED", null, twoGroceries, at);    // not a correction
        UUID mi = merchant(internal, name);
        events(50, internal, mi, "CORRECTED", category(internal, shopping), category(internal, food), at);

        JsonNode stats = get(admin, STATS);
        JsonNode merchants = stats.get("topCorrectedMerchants");
        assertThat(merchants.size()).isLessThanOrEqualTo(10);
        JsonNode row = null;
        for (JsonNode m : merchants) {
            if (m.get("merchant").asText().equalsIgnoreCase(name)) {
                assertThat(row).as("one row for every spelling").isNull();
                row = m;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("corrections").asLong()).isEqualTo(45);
        assertThat(row.get("users").asLong()).isEqualTo(2);
        assertThat(row.get("usualFrom").asText()).isEqualTo(food);
        assertThat(row.get("usualTo").asText()).isEqualTo(groceries);
        List<Long> counts = new ArrayList<>();
        merchants.forEach(m -> counts.add(m.get("corrections").asLong()));
        assertThat(counts).isSortedAccordingTo((a, b) -> Long.compare(b, a));

        long foodToGroceries = 0;
        long shoppingToGroceries = 0;
        long shoppingToFood = 0;
        for (JsonNode c : stats.get("topCategoryChanges")) {
            String from = c.get("fromCategory").asText();
            String to = c.get("toCategory").asText();
            if (from.equals(food) && to.equals(groceries)) foodToGroceries = c.get("count").asLong();
            if (from.equals(shopping) && to.equals(groceries)) shoppingToGroceries = c.get("count").asLong();
            if (from.equals(shopping) && to.equals(food)) shoppingToFood = c.get("count").asLong();
        }
        assertThat(foodToGroceries).isEqualTo(30);
        assertThat(shoppingToGroceries).isEqualTo(10);
        assertThat(shoppingToFood).as("internal account's corrections are left out").isZero();
    }

    @Test
    void recentEventsAreNewestFirstCustomersOnlyAndPaged() throws Exception {
        User admin = admin();
        User customer = customer(false);
        User internal = customer(true);
        UUID merchant = merchant(customer, " LE IT Events " + tag + " ");
        UUID from = category(customer, "LE IT From " + tag);
        UUID to = category(customer, "LE IT To " + tag);
        // An hour ahead, so these are the newest rows in the shared table.
        Instant soon = Instant.now().plus(Duration.ofHours(1));
        events(1, customer, merchant, "LEARNED", null, from, soon);
        events(1, customer, merchant, "CORRECTED", from, to, soon.plusSeconds(60));
        events(1, internal, merchant(internal, "LE IT Events internal " + tag), "LEARNED", null, null,
                soon.plusSeconds(120));

        JsonNode page = get(admin, EVENTS + "?page=0&size=2");
        JsonNode content = page.get("content");
        assertThat(content.size()).isEqualTo(2);
        JsonNode newest = content.get(0);
        assertThat(newest.get("action").asText()).isEqualTo("CORRECTED");
        assertThat(newest.get("userId").asText()).isEqualTo(customer.getId().toString());
        assertThat(newest.get("userEmail").asText()).isEqualTo(customer.getEmail());
        assertThat(newest.get("merchant").asText()).isEqualTo("LE IT Events " + tag);
        assertThat(newest.get("fromCategory").asText()).isEqualTo("LE IT From " + tag);
        assertThat(newest.get("toCategory").asText()).isEqualTo("LE IT To " + tag);
        JsonNode second = content.get(1);
        assertThat(second.get("action").asText()).isEqualTo("LEARNED");
        assertThat(second.get("fromCategory").isNull()).isTrue();
        assertThat(page.get("size").asInt()).isEqualTo(2);
        assertThat(page.get("totalPages").asLong()).isEqualTo((page.get("totalElements").asLong() + 1) / 2);

        JsonNode clamped = get(admin, EVENTS + "?page=-3&size=5000");
        assertThat(clamped.get("size").asInt()).isEqualTo(100);
        assertThat(clamped.get("page").asInt()).isZero();
        assertThat(clamped.get("content").size()).isLessThanOrEqualTo(100);
    }
}
