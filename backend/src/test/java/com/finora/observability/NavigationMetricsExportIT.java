package com.finora.observability;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ClientPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the navigation counters are actually scrapeable, rather than merely registered.
 *
 * <p>Same reason {@link WorkerMetricsExportIT} exists, and mirrors its setup: a meter in a registry
 * nothing exports is exactly as useful as no meter at all, and looks identical in a unit test.
 * {@link NavigationMetricsTest} proves the counters increment; only this proves a dashboard could
 * ever read them.
 *
 * <p>That distinction matters more than usual here. These counters exist to establish a baseline
 * before the navigation taxonomy changes, and there is no backfill -- a counter that increments but
 * never reaches the scrape would be discovered only after the before-period had already been lost.
 */
class NavigationMetricsExportIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private NavigationMetrics navigationMetrics;

    private String scrape() {
        // Anonymous, and on the management port: the scrape no longer carries a credential.
        // See ManagementPortIsolationIT for why that is safe.
        ResponseEntity<String> response =
                restTemplate.getForEntity(actuatorUrl("prometheus"), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("the scrape must work; without it every navigation meter is invisible")
                .isTrue();
        return response.getBody();
    }

    @Test
    void navigationMetricsAppearInTheScrape() {
        navigationMetrics.destinationOpened(
                NavDestination.BUDGETS, NavGroup.PLANNING, ClientPlatform.WEB);
        navigationMetrics.entryPointUsed(NavEntryPoint.TAB, ClientPlatform.MOBILE_ANDROID);
        navigationMetrics.searchUsed(ClientPlatform.WEB);

        String body = scrape();

        // Prometheus renders dots as underscores, so these are the names a dashboard query uses.
        assertThat(body)
                .contains("finora_nav_destination_opened")
                .contains("finora_nav_entry_point_used")
                .contains("finora_nav_search_used");
    }

    @Test
    void theTagsADashboardWouldGroupBySurviveTheScrape() {
        // The counters are only useful split by these dimensions -- "which destinations are used"
        // and "web versus mobile" are the two questions they exist to answer. A meter that exports
        // without its tags answers neither, and would still pass the test above.
        navigationMetrics.destinationOpened(
                NavDestination.GOALS, NavGroup.PLANNING, ClientPlatform.MOBILE_IOS);

        String body = scrape();

        assertThat(body)
                .contains("destination=\"goals\"")
                .contains("group=\"planning\"")
                .contains("platform=\"mobile_ios\"");
    }
}
