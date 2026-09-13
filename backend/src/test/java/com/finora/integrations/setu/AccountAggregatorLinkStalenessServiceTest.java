package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkStalenessServiceTest {

    // 24h cadence -> 72h threshold, same default the scope doc settled on.
    private final AccountAggregatorLinkStalenessService staleness =
            new AccountAggregatorLinkStalenessService(24);

    private AccountAggregatorLink linkSyncedAgo(Duration ago) {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setLastSyncedAt(Instant.now().minus(ago));
        return link;
    }

    private AccountAggregatorLink linkNeverSyncedUpdatedAgo(Duration ago) {
        AccountAggregatorLink link = new AccountAggregatorLink();
        // lastSyncedAt stays null (default). updatedAt is set on construction by the entity's own
        // field initializer -- overridden here via reflection to simulate a link that has been
        // sitting untouched since that duration ago.
        ReflectionTestUtils.setField(link, "updatedAt", Instant.now().minus(ago));
        return link;
    }

    @Test
    void notStaleWhenSyncedWellWithinTheThreshold() {
        assertThat(staleness.isStale(linkSyncedAgo(Duration.ofHours(1)))).isFalse();
    }

    @Test
    void staleWhenLastSyncIsWellPastTheThreshold() {
        assertThat(staleness.isStale(linkSyncedAgo(Duration.ofHours(96)))).isTrue();
    }

    @Test
    void boundaryIsExclusive_justUnderSeventyTwoHoursIsNotYetStale_justOverIs() {
        // "exceeds 3x the expected cadence" (design spec) -- the comparison is a strict >, not >=.
        // A one-minute margin on both sides of the 72h threshold (rather than the exact instant)
        // absorbs the unavoidable few milliseconds of test-execution time between constructing the
        // fixture's "ago" timestamp and isStale()'s own Instant.now() call -- asserting on the
        // literal nanosecond boundary would make this test flaky by construction, not a real
        // guarantee about the implementation.
        assertThat(staleness.isStale(linkSyncedAgo(Duration.ofHours(72).minusMinutes(1)))).isFalse();
        assertThat(staleness.isStale(linkSyncedAgo(Duration.ofHours(72).plusMinutes(1)))).isTrue();
    }

    @Test
    void aFreshlyActivatedNeverSyncedLinkIsNotStale() {
        // The narrow real race the scope doc traced: attach() saves ACTIVE, then the backfill
        // sync() call sets lastSyncedAt a moment later. A link only seconds old with lastSyncedAt
        // still null must not immediately open the hatch.
        assertThat(staleness.isStale(linkNeverSyncedUpdatedAgo(Duration.ofSeconds(5)))).isFalse();
    }

    @Test
    void aNeverSyncedLinkStillSittingThereAfterSeventyTwoHoursIsStale() {
        // Entitlement lapsed right at activation, or Setu credentials missing -- both leave
        // lastSyncedAt permanently null. Same 72h threshold as any other stale link, not a
        // separate shorter constant.
        assertThat(staleness.isStale(linkNeverSyncedUpdatedAgo(Duration.ofHours(96)))).isTrue();
    }
}
