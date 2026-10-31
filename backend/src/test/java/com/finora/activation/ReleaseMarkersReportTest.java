package com.finora.activation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release markers on the activation report: the signups either side of a marker, over windows of the
 * same number of days, by main-path step reached.
 */
class ReleaseMarkersReportTest {

    private static final SignupSources.Source DIRECT = new SignupSources.Source(SignupSourceKind.DIRECT, null);
    private static final LocalDate TODAY = LocalDate.of(2030, 6, 20);
    private static final LocalDate MARKER = LocalDate.of(2030, 6, 11); // 10 days to today, inclusive

    private static ActivationFunnelService.Counted signup(LocalDate day, FunnelStep step, boolean cameBack) {
        return new ActivationFunnelService.Counted(step, day.with(java.time.DayOfWeek.MONDAY), null, DIRECT,
                cameBack ? 1.0 : null, cameBack, null, false, day);
    }

    private static ActivationReportDto.ReleaseMarker only(List<ActivationFunnelService.Counted> people, LocalDate marker) {
        List<ActivationReportDto.ReleaseMarker> out = ActivationFunnelService.releaseMarkers(
                List.of(new ActivationFunnelService.Marker(7, marker, "Gate 2b live")), people, TODAY);
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    @Test
    void windowsAreTheSameLength_afterFromTheMarkerToToday_beforeEndingTheDayBefore() {
        var m = only(List.of(), MARKER);

        assertThat(m.id()).isEqualTo(7);
        assertThat(m.label()).isEqualTo("Gate 2b live");
        assertThat(m.after().from()).isEqualTo(MARKER);
        assertThat(m.after().to()).isEqualTo(TODAY);
        assertThat(m.before().from()).isEqualTo(LocalDate.of(2030, 6, 1));
        assertThat(m.before().to()).isEqualTo(LocalDate.of(2030, 6, 10));
    }

    @Test
    void eachSignupFallsInTheWindowOfItsDay_andTheEdgesAreInclusive() {
        var m = only(List.of(
                signup(LocalDate.of(2030, 5, 31), FunnelStep.GOT_DATA_IN, false),   // before the before window
                signup(LocalDate.of(2030, 6, 1), FunnelStep.SIGNED_UP, false),      // first day of before
                signup(LocalDate.of(2030, 6, 10), FunnelStep.GOT_DATA_IN, true),    // last day of before
                signup(MARKER, FunnelStep.REFUSED, false),                          // the marker's day is after
                signup(TODAY, FunnelStep.GOT_DATA_IN, false),                       // today is after
                signup(TODAY, FunnelStep.OPENED_IMPORT, false)), MARKER);

        assertThat(m.before().signups()).isEqualTo(2);
        assertThat(m.before().reached().get(ReachedStep.GOT_DATA_IN)).isEqualTo(1);
        assertThat(m.before().cameBackLaterDay()).isEqualTo(1);
        assertThat(m.after().signups()).isEqualTo(3);
        assertThat(m.after().reached())
                .containsEntry(ReachedStep.SIGNED_UP, 3L)
                .containsEntry(ReachedStep.OPENED_IMPORT, 3L)
                .containsEntry(ReachedStep.CHOSE_FILE, 2L)
                .containsEntry(ReachedStep.FILE_READ, 1L)
                .containsEntry(ReachedStep.GOT_DATA_IN, 1L);
        assertThat(m.after().cameBackLaterDay()).isZero();
    }

    @Test
    void aMarkerDatedTodayComparesTodayWithYesterday() {
        var m = only(List.of(signup(TODAY.minusDays(1), FunnelStep.SIGNED_UP, false)), TODAY);

        assertThat(m.after().from()).isEqualTo(TODAY);
        assertThat(m.after().to()).isEqualTo(TODAY);
        assertThat(m.before().from()).isEqualTo(TODAY.minusDays(1));
        assertThat(m.before().to()).isEqualTo(TODAY.minusDays(1));
        assertThat(m.before().signups()).isEqualTo(1);
        assertThat(m.after().signups()).isZero();
    }

    @Test
    void aMarkerStillToComeHasNoWindows() {
        var m = only(List.of(signup(TODAY, FunnelStep.SIGNED_UP, false)), TODAY.plusDays(1));

        assertThat(m.before()).isNull();
        assertThat(m.after()).isNull();
    }

    @Test
    void withNoSignups_everyStepIsPresentWithZeros() {
        var m = only(List.of(), MARKER);

        for (var window : List.of(m.before(), m.after())) {
            assertThat(window.signups()).isZero();
            assertThat(window.reached()).hasSize(ReachedStep.values().length);
            assertThat(window.reached().values()).containsOnly(0L);
            assertThat(window.cameBackLaterDay()).isZero();
        }
    }

    @Test
    void noMarkers_noRows_andMarkersKeepTheirOrder() {
        assertThat(ActivationFunnelService.releaseMarkers(List.of(), List.of(), TODAY)).isEmpty();
        var out = ActivationFunnelService.releaseMarkers(List.of(
                new ActivationFunnelService.Marker(1, MARKER, "First"),
                new ActivationFunnelService.Marker(2, MARKER, "Same day")), List.of(), TODAY);
        assertThat(out).extracting("label").containsExactly("First", "Same day");
    }
}
