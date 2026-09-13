package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkSyncStatusTest {

    @Test
    void lastSyncStatusIsNullUntilTheFirstFetchAttempt() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        assertThat(link.getLastSyncStatus()).isNull();
    }

    @Test
    void canRecordASuccessfulOrFailedFetch() {
        AccountAggregatorLink link = new AccountAggregatorLink();

        link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.SUCCESS);
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);

        link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.FAILED);
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.FAILED);
    }
}
