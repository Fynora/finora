package com.finora.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins which live Razorpay plan V224 points new Plus checkouts at. This is checked against the
 * migration file rather than the database on purpose: the integration suite shares one Postgres,
 * and BillingControllerIT / RazorpayWebhookDispatcherIT overwrite the seeded rows' razorpay_plan_id
 * with throwaway test ids, so the value read back there depends on test order. The migration is
 * what actually runs in production, so it is the thing worth pinning. BillingPriceRepositoryIT
 * covers the migrated prices and the kept-but-inactive old rows against a real database.
 */
class V224MigrationTest {

    private static final String MIGRATION = "db/migration/V224__reprice_plus_249_1999_incl_gst.sql";

    private static String sql() throws IOException {
        try (InputStream in = V224MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Each insert pairs the right cycle, the GST-inclusive amount, and the plan created for it on 2026-09-24. */
    @Test
    void insertsMonthlyAt249AndYearlyAt1999_onTheirNewRazorpayPlans() throws IOException {
        String sql = sql();

        assertThat(sql).containsPattern(Pattern.compile(
                "'MONTHLY',\\s*249\\.00,\\s*'INR',\\s*'plan_TfqXw7GNqwcUt6'"));
        assertThat(sql).containsPattern(Pattern.compile(
                "'YEARLY',\\s*1999\\.00,\\s*'INR',\\s*'plan_TfqYQovys3Dsg6'"));
    }

    /** The unique index allows one active row per (plan, cycle): the old rows must be switched off first. */
    @Test
    void deactivatesTheOldPlusRowsBeforeInsertingTheNewOnes() throws IOException {
        String sql = sql();

        int deactivate = sql.indexOf("UPDATE billing_prices SET active = false");
        int firstInsert = sql.indexOf("INSERT INTO billing_prices");

        assertThat(deactivate).isNotNegative();
        assertThat(firstInsert).isGreaterThan(deactivate);
    }
}
