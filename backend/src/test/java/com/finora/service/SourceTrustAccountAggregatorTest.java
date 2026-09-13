package com.finora.service;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTrustAccountAggregatorTest {

    @Test
    void accountAggregatorRanksAboveGmailAndBelowCsv() {
        // Trust 70 per the AA sync design spec -- deliberately below CSV_IMPORT/95 despite AA being
        // a live bank feed, because the decrypt/map layer (Plan 2) ships with zero production
        // mileage. See docs/superpowers/plans/2026-09-13-account-aggregator-transaction-sync.md's
        // "Known spec divergence" section for why this isn't 100.
        int aaTrust = SourceTrust.of(Transaction.Source.ACCOUNT_AGGREGATOR);
        assertThat(aaTrust).isEqualTo(70);
        assertThat(aaTrust).isGreaterThan(SourceTrust.of(Transaction.Source.GMAIL_IMPORT));
        assertThat(aaTrust).isLessThan(SourceTrust.of(Transaction.Source.CSV_IMPORT));
    }
}
