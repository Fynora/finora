package com.finora.service;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V334 (audit F-12): a shared category that is already trusted, where some account voted more than
 * once, is sent to be counted again under one vote per account. Flyway has already applied the file
 * to the empty test database, so it runs here again over rows shaped the way the old count left
 * them, and then the nightly sweep's own method counts them. Every value is synthetic, and each
 * test uses a shop of its own: these tables are shared by the whole suite.
 */
class V334RecountSharedCategoriesIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private SharedCorpusService sharedCorpusService;

    private static String shop() {
        return "vpa:v334it" + UUID.randomUUID().toString().replace("-", "");
    }

    private void vote(String shop, UUID user, String category) {
        jdbc.update("""
                INSERT INTO counterparty_category_observation
                    (counterparty_key, direction, category, user_id, counterparty_type_at_vote)
                VALUES (?, 'EXPENSE', ?, ?, 'BUSINESS')
                """, shop, category, user);
    }

    private void trusted(String shop, String category) {
        jdbc.update("""
                INSERT INTO shared_merchant_category
                    (counterparty_key, direction, status, category, category_distribution, distinct_user_count)
                VALUES (?, 'EXPENSE', 'TRUSTED', ?, '{}'::jsonb, 3)
                """, shop, category);
    }

    private List<String> statusAndCategory(String shop) {
        return jdbc.query("SELECT status, category FROM shared_merchant_category WHERE counterparty_key = ?",
                (rs, i) -> rs.getString(1) + ":" + rs.getString(2), shop);
    }

    private void runV334() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(
                    "db/migration/V334__recount_shared_categories_one_vote_per_account.sql")));
        }
    }

    @Test
    void aRowTrustedOnOneAccountsRepeatVotes_stopsBeingSuggested_andIsDisputedOnceCounted() throws Exception {
        String shop = shop();
        UUID loud = UUID.randomUUID();
        for (int i = 0; i < 10; i++) vote(shop, loud, "Investments");
        vote(shop, UUID.randomUUID(), "Dining");
        vote(shop, UUID.randomUUID(), "Dining");
        trusted(shop, "Investments");

        runV334();

        assertThat(statusAndCategory(shop)).containsExactly("REVALIDATING:Investments");

        sharedCorpusService.reevaluateTimedOutRevalidations(500);

        assertThat(statusAndCategory(shop)).containsExactly("DISPUTED:Dining");
    }

    @Test
    void aRowThreeAccountsReallyAgreeOn_isTrustedAgainOnceCounted() throws Exception {
        String shop = shop();
        UUID twice = UUID.randomUUID();
        vote(shop, twice, "Dining");
        vote(shop, twice, "Dining");
        vote(shop, UUID.randomUUID(), "Dining");
        vote(shop, UUID.randomUUID(), "Dining");
        trusted(shop, "Dining");

        runV334();
        assertThat(statusAndCategory(shop)).containsExactly("REVALIDATING:Dining");

        sharedCorpusService.reevaluateTimedOutRevalidations(500);

        assertThat(statusAndCategory(shop)).containsExactly("TRUSTED:Dining");
    }

    @Test
    void aRowWhereOneAccountAloneVoted_isRemovedOnceCounted() throws Exception {
        String shop = shop();
        UUID only = UUID.randomUUID();
        for (int i = 0; i < 3; i++) vote(shop, only, "Dining");
        trusted(shop, "Dining");

        runV334();
        sharedCorpusService.reevaluateTimedOutRevalidations(500);

        assertThat(statusAndCategory(shop)).isEmpty();
    }

    @Test
    void aRowWhereEveryAccountVotedOnce_isLeftAlone() throws Exception {
        String shop = shop();
        for (int i = 0; i < 3; i++) vote(shop, UUID.randomUUID(), "Dining");
        trusted(shop, "Dining");
        Object before = jdbc.queryForObject(
                "SELECT last_recomputed_at FROM shared_merchant_category WHERE counterparty_key = ?", Object.class, shop);

        runV334();

        assertThat(statusAndCategory(shop)).containsExactly("TRUSTED:Dining");
        assertThat(jdbc.queryForObject(
                "SELECT last_recomputed_at FROM shared_merchant_category WHERE counterparty_key = ?", Object.class, shop))
                .isEqualTo(before);
    }

    private void disputed(String shop, String category) {
        jdbc.update("""
                INSERT INTO shared_merchant_category
                    (counterparty_key, direction, status, category, category_distribution, distinct_user_count)
                VALUES (?, 'EXPENSE', 'DISPUTED', ?, '{}'::jsonb, 3)
                """, shop, category);
    }

    /** The other direction: one account's repeat votes against three that agree used to hold the
     *  winner under 70%, and the shop was never suggested. */
    @Test
    void aRowKeptDisputedByOneAccountsRepeatVotes_isTrustedOnceCounted() throws Exception {
        String shop = shop();
        UUID loud = UUID.randomUUID();
        for (int i = 0; i < 10; i++) vote(shop, loud, "Shopping");
        for (int i = 0; i < 3; i++) vote(shop, UUID.randomUUID(), "Dining");
        disputed(shop, "Shopping");

        runV334();
        assertThat(statusAndCategory(shop)).containsExactly("REVALIDATING:Shopping");

        sharedCorpusService.reevaluateTimedOutRevalidations(500);

        assertThat(statusAndCategory(shop)).containsExactly("TRUSTED:Dining");
    }

    @Test
    void aDisputedRowWhereEveryAccountVotedOnce_isLeftAlone_andRunningTwiceChangesNothingMore() throws Exception {
        String disputed = shop();
        vote(disputed, UUID.randomUUID(), "Dining");
        vote(disputed, UUID.randomUUID(), "Shopping");
        vote(disputed, UUID.randomUUID(), "Groceries");
        disputed(disputed, "Dining");
        UUID twice = UUID.randomUUID();
        String flipped = shop();
        vote(flipped, twice, "Dining");
        vote(flipped, twice, "Dining");
        trusted(flipped, "Dining");

        runV334();
        Object once = jdbc.queryForObject(
                "SELECT revalidating_since FROM shared_merchant_category WHERE counterparty_key = ?", Object.class, flipped);
        runV334();

        assertThat(statusAndCategory(disputed)).containsExactly("DISPUTED:Dining");
        assertThat(jdbc.queryForObject(
                "SELECT revalidating_since FROM shared_merchant_category WHERE counterparty_key = ?", Object.class, flipped))
                .isEqualTo(once);
    }
}
