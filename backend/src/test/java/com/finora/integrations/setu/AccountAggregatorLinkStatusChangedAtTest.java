package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkStatusChangedAtTest {

    @Test
    void statusChangedAtIsSetOnConstructionAndUpdatedOnEveryStatusChange() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        Instant initial = link.getStatusChangedAt();
        assertThat(initial).isNotNull();

        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        Instant afterFirstChange = link.getStatusChangedAt();
        assertThat(afterFirstChange).isAfterOrEqualTo(initial);

        // Setting other fields must NOT move statusChangedAt -- only a real status transition does,
        // which is the whole reason this field exists instead of reusing updatedAt (already touched
        // by every sync via setLastSyncedAt).
        link.setLastSyncedAt(Instant.now());
        assertThat(link.getStatusChangedAt()).isEqualTo(afterFirstChange);
    }
}
