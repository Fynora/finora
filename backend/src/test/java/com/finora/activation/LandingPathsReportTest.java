package com.finora.activation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V300: the activation report's sign-ups-by-landing-page aggregate. Counts only, for accounts made
 * after landing paths began to be recorded, with the busiest paths named and the rest summed.
 */
class LandingPathsReportTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final SignupSources.Source DIRECT = new SignupSources.Source(SignupSourceKind.DIRECT, null);

    private static ActivationFunnelService.Counted user(FunnelStep step, String landingPath, boolean beforeTracking) {
        return new ActivationFunnelService.Counted(step, LocalDate.of(2026, 11, 2), null, DIRECT, null, false,
                landingPath, beforeTracking);
    }

    private static ActivationFunnelService.Counted cameBack(String landingPath) {
        return new ActivationFunnelService.Counted(FunnelStep.GOT_DATA_IN, LocalDate.of(2026, 11, 2), null, DIRECT, 2.0, true,
                landingPath, false);
    }

    /** Came back another day, per page: the same measure as the second import, by landing page. */
    @Test
    void countsWhoCameBackAnotherDayPerPath_andInTheOtherAndNoneRows() {
        var report = ActivationFunnelService.landingPaths(users(
                cameBack("/help"), cameBack("/help"), user(FunnelStep.GOT_DATA_IN, "/help", false),
                user(FunnelStep.SIGNED_UP, "/about", false),
                cameBack(null)), START);

        assertThat(report.pages()).extracting("path", "signups", "gotDataIn", "cameBackLaterDay").containsExactly(
                org.assertj.core.groups.Tuple.tuple("/help", 3L, 3L, 2L),
                org.assertj.core.groups.Tuple.tuple("/about", 1L, 0L, 0L));
        assertThat(report.none().cameBackLaterDay()).isEqualTo(1);
        assertThat(report.other().cameBackLaterDay()).isZero();
    }

    private static Map<UUID, ActivationFunnelService.Counted> users(ActivationFunnelService.Counted... all) {
        Map<UUID, ActivationFunnelService.Counted> map = new HashMap<>();
        for (ActivationFunnelService.Counted c : all) map.put(UUID.randomUUID(), c);
        return map;
    }

    @Test
    void countsSignupsAndGotDataInPerPath_mostSignupsFirst_thenByPath() {
        var report = ActivationFunnelService.landingPaths(users(
                user(FunnelStep.GOT_DATA_IN, "/help", false),
                user(FunnelStep.SIGNED_UP, "/help", false),
                user(FunnelStep.CHOSE_FILE, "/help", false),
                user(FunnelStep.GOT_DATA_IN, "/banks/example-bank", false),
                user(FunnelStep.SIGNED_UP, "/about", false),
                user(FunnelStep.SIGNED_UP, "/", false)), START);

        assertThat(report.trackingStart()).isEqualTo(START);
        assertThat(report.pages()).extracting("path", "signups", "gotDataIn").containsExactly(
                org.assertj.core.groups.Tuple.tuple("/help", 3L, 1L),
                org.assertj.core.groups.Tuple.tuple("/", 1L, 0L),
                org.assertj.core.groups.Tuple.tuple("/about", 1L, 0L),
                org.assertj.core.groups.Tuple.tuple("/banks/example-bank", 1L, 1L));
    }

    @Test
    void accountsWithNoLandingPathAreOneNoneRow_andAccountsBeforeTrackingAreLeftOutEntirely() {
        var report = ActivationFunnelService.landingPaths(users(
                user(FunnelStep.GOT_DATA_IN, null, false),
                user(FunnelStep.SIGNED_UP, null, false),
                user(FunnelStep.GOT_DATA_IN, "/help", true),   // made before: neither a page nor "none"
                user(FunnelStep.SIGNED_UP, null, true)), START);

        assertThat(report.none().path()).isNull();
        assertThat(report.none().signups()).isEqualTo(2);
        assertThat(report.none().gotDataIn()).isEqualTo(1);
        assertThat(report.pages()).isEmpty();
        assertThat(report.other().signups()).isZero();
    }

    @Test
    void whenTheTrackingStartIsNotKnown_nobodyIsCounted() {
        var report = ActivationFunnelService.landingPaths(users(
                user(FunnelStep.GOT_DATA_IN, "/help", false),
                user(FunnelStep.SIGNED_UP, null, false)), null);

        assertThat(report.trackingStart()).isNull();
        assertThat(report.pages()).isEmpty();
        assertThat(report.none().signups()).isZero();
        assertThat(report.other().signups()).isZero();
    }

    /** Past the cap, the quietest paths are summed into one "other" row: a flood of made-up paths
     *  posted to the sign-up endpoint cannot grow the report. */
    @Test
    void pathsBeyondTheCapAreSummedIntoOther() {
        int extra = 4;
        var all = new java.util.ArrayList<ActivationFunnelService.Counted>();
        for (int i = 0; i < ActivationFunnelService.MAX_LANDING_PATHS + extra; i++) {
            String path = "/page-" + String.format("%03d", i);
            // Page i has (total - i) sign-ups, so the last `extra` pages are the quietest, with 1..extra.
            int signups = ActivationFunnelService.MAX_LANDING_PATHS + extra - i;
            for (int n = 0; n < signups; n++) {
                all.add(n == 0 ? cameBack(path) : user(FunnelStep.SIGNED_UP, path, false));
            }
        }
        var report = ActivationFunnelService.landingPaths(users(all.toArray(new ActivationFunnelService.Counted[0])), START);

        assertThat(report.pages()).hasSize(ActivationFunnelService.MAX_LANDING_PATHS);
        assertThat(report.pages().get(0).path()).isEqualTo("/page-000");
        assertThat(report.pages().get(ActivationFunnelService.MAX_LANDING_PATHS - 1).path()).isEqualTo("/page-024");
        assertThat(report.other().path()).isNull();
        assertThat(report.other().signups()).isEqualTo(1 + 2 + 3 + 4);
        assertThat(report.other().gotDataIn()).isEqualTo(extra);
        assertThat(report.other().cameBackLaterDay()).isEqualTo(extra);
        long total = report.pages().stream().mapToLong(ActivationReportDto.LandingPathRow::signups).sum()
                + report.other().signups() + report.none().signups();
        assertThat(total).as("every counted account is in exactly one row").isEqualTo(all.size());
    }

    @Test
    void anEmptyPopulationGivesEmptyRowsNotNulls() {
        var report = ActivationFunnelService.landingPaths(Map.of(), START);

        assertThat(report.pages()).isEmpty();
        assertThat(report.other()).isEqualTo(new ActivationReportDto.LandingPathRow(null, 0, 0, 0));
        assertThat(report.none()).isEqualTo(new ActivationReportDto.LandingPathRow(null, 0, 0, 0));
    }
}
