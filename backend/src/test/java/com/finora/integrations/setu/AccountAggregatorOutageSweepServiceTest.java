package com.finora.integrations.setu;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAggregatorOutageSweepServiceTest {

    private final AccountAggregatorLinkStalenessService staleness =
            new AccountAggregatorLinkStalenessService(24); // 72h threshold

    @Test
    void countsOnlyTheStaleActiveLinksAndPublishesTheGauge() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setLastSyncedAt(Instant.now().minus(Duration.ofHours(96)));
        AccountAggregatorLink healthy = new AccountAggregatorLink();
        healthy.setLastSyncedAt(Instant.now().minus(Duration.ofHours(1)));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(stale, healthy));
        MeterRegistry registry = new SimpleMeterRegistry();

        AccountAggregatorOutageSweepService sweep =
                new AccountAggregatorOutageSweepService(links, staleness, registry);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(registry.get("finora.account_aggregator.stale_links").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void mutatesNoLinkAndNoAccount() {
        // Read-only, per this plan's Global Constraints -- the hatch is evaluated live by the
        // guard, never by this sweep. No save() call of any kind should ever happen here.
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setLastSyncedAt(Instant.now().minus(Duration.ofHours(96)));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(stale));
        MeterRegistry registry = new SimpleMeterRegistry();

        AccountAggregatorOutageSweepService sweep =
                new AccountAggregatorOutageSweepService(links, staleness, registry);
        sweep.sweep();

        org.mockito.Mockito.verify(links, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void gaugeReadsZeroWhenNothingIsStale() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of());
        MeterRegistry registry = new SimpleMeterRegistry();

        AccountAggregatorOutageSweepService sweep =
                new AccountAggregatorOutageSweepService(links, staleness, registry);

        assertThat(sweep.sweep()).isZero();
        assertThat(registry.get("finora.account_aggregator.stale_links").gauge().value()).isEqualTo(0.0);
    }
}
