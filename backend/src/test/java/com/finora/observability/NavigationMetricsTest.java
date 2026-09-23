package com.finora.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NavigationMetrics metrics = new NavigationMetrics(registry);

    @Test
    void destinationOpened_incrementsWithDestinationGroupAndPlatformTags() {
        metrics.destinationOpened(NavDestination.BUDGETS, NavGroup.PLANNING, "web");

        assertThat(registry.get("finora.nav.destination_opened")
                .tag("destination", "budgets")
                .tag("group", "planning")
                .tag("platform", "web")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void entryPointUsed_incrementsWithEntryAndPlatformTags() {
        metrics.entryPointUsed(NavEntryPoint.TAB, "mobile");

        assertThat(registry.get("finora.nav.entry_point_used")
                .tag("entry", "tab").tag("platform", "mobile")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void searchUsed_incrementsWithPlatformTagOnly() {
        metrics.searchUsed("web");

        assertThat(registry.get("finora.nav.search_used")
                .tag("platform", "web").counter().count()).isEqualTo(1.0);
    }

    @Test
    void fromWire_returnsEmptyForAnUnknownValue_neverAnOtherBucket() {
        assertThat(NavDestination.fromWire("definitely-not-a-destination")).isEmpty();
        assertThat(NavGroup.fromWire("../../etc/passwd")).isEmpty();
        assertThat(NavEntryPoint.fromWire("")).isEmpty();
    }
}
