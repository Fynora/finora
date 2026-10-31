package com.finora.activation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The reached view of the activation funnel: how many signups reached each main-path step, the drop
 * between neighbours, and the same by signup week. Pure aggregation over the per-user rows.
 */
class ReachedFunnelReportTest {

    private static final SignupSources.Source DIRECT = new SignupSources.Source(SignupSourceKind.DIRECT, null);
    /** A Monday: the week every user below signs up in unless said otherwise. */
    private static final LocalDate WEEK = LocalDate.of(2030, 6, 3);
    private static final LocalDate GO_LIVE = LocalDate.of(2030, 6, 1);

    private static ActivationFunnelService.Counted user(FunnelStep step) {
        return user(step, WEEK, WEEK.plusDays(2), false);
    }

    private static ActivationFunnelService.Counted user(FunnelStep step, LocalDate week, LocalDate created, boolean cameBack) {
        return new ActivationFunnelService.Counted(step, week, null, DIRECT, cameBack ? 1.0 : null, cameBack,
                null, false, created);
    }

    private static List<ActivationFunnelService.Counted> many(FunnelStep step, int n) {
        List<ActivationFunnelService.Counted> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(user(step));
        return out;
    }

    @Test
    void zeroSignups_everyStepIsPresentWithZeros() {
        var reached = ActivationFunnelService.reached(List.of(), GO_LIVE);

        assertThat(reached.signups()).isZero();
        assertThat(reached.steps()).extracting("step", "reached", "lost", "lostBeforeFunnelRecord").containsExactly(
                tuple(ReachedStep.SIGNED_UP, 0L, 0L, 0L),
                tuple(ReachedStep.OPENED_IMPORT, 0L, 0L, 0L),
                tuple(ReachedStep.CHOSE_FILE, 0L, 0L, 0L),
                tuple(ReachedStep.FILE_READ, 0L, 0L, 0L),
                tuple(ReachedStep.REVIEW_OPENED, 0L, 0L, 0L),
                tuple(ReachedStep.GOT_DATA_IN, 0L, 0L, 0L));
        assertThat(reached.stoppedAt()).hasSize(FunnelStep.values().length).allSatisfy((step, n) -> assertThat(n).isZero());
    }

    @Test
    void reachedIsCumulative_andEachDropIsThePeopleStoppedBetweenTheTwoSteps() {
        List<ActivationFunnelService.Counted> people = new ArrayList<>();
        people.addAll(many(FunnelStep.SIGNED_UP, 5));
        people.addAll(many(FunnelStep.OPENED_IMPORT, 2));
        people.addAll(many(FunnelStep.CHOSE_FILE, 1));
        people.addAll(many(FunnelStep.REFUSED, 1));
        people.addAll(many(FunnelStep.READ_FAILED, 1));
        people.addAll(many(FunnelStep.HELD, 1));
        people.addAll(many(FunnelStep.READ_NOT_REVIEWED, 1));
        people.addAll(many(FunnelStep.REVIEWED_NOT_CONFIRMED, 1));
        people.addAll(many(FunnelStep.GOT_DATA_IN, 3));

        var reached = ActivationFunnelService.reached(people, GO_LIVE);

        assertThat(reached.signups()).isEqualTo(16);
        assertThat(reached.steps()).extracting("step", "reached", "lost").containsExactly(
                tuple(ReachedStep.SIGNED_UP, 16L, 0L),
                tuple(ReachedStep.OPENED_IMPORT, 11L, 5L),
                tuple(ReachedStep.CHOSE_FILE, 9L, 2L),
                // Refused, read failed and held all stopped before a file was read.
                tuple(ReachedStep.FILE_READ, 5L, 4L),
                tuple(ReachedStep.REVIEW_OPENED, 4L, 1L),
                tuple(ReachedStep.GOT_DATA_IN, 3L, 1L));
        assertThat(reached.stoppedAt().get(FunnelStep.REFUSED)).isEqualTo(1);
        assertThat(reached.stoppedAt().get(FunnelStep.GOT_DATA_IN)).isEqualTo(3);
    }

    @Test
    void everyoneAtOneStep_reachesEverythingUpToIt_andNothingAfter() {
        var allIn = ActivationFunnelService.reached(many(FunnelStep.GOT_DATA_IN, 4), GO_LIVE);
        assertThat(allIn.steps()).extracting("reached").containsOnly(4L);
        assertThat(allIn.steps()).extracting("lost").containsOnly(0L);

        var allSignedUp = ActivationFunnelService.reached(many(FunnelStep.SIGNED_UP, 4), GO_LIVE);
        assertThat(allSignedUp.steps()).extracting("reached").containsExactly(4L, 0L, 0L, 0L, 0L, 0L);
        assertThat(allSignedUp.steps()).extracting("lost").containsExactly(0L, 4L, 0L, 0L, 0L, 0L);
    }

    /**
     * Opening import, choosing a file and opening the review are known only from the import-funnel
     * record. People who signed up before its first day and show as stopped short of such a step may
     * have reached it unrecorded; the drop says how many of them are in it.
     */
    @Test
    void dropsIntoAStepKnownOnlyFromTheFunnelRecord_countWhoSignedUpBeforeItBegan() {
        List<ActivationFunnelService.Counted> people = List.of(
                user(FunnelStep.SIGNED_UP, WEEK, GO_LIVE.minusDays(1), false),           // before: in the drop
                user(FunnelStep.SIGNED_UP, WEEK, GO_LIVE, false),                        // on the first day: recorded
                user(FunnelStep.READ_NOT_REVIEWED, WEEK, GO_LIVE.minusDays(3), false),   // before: review drop
                user(FunnelStep.REFUSED, WEEK, GO_LIVE.minusDays(3), false),             // FILE_READ is not from the record
                user(FunnelStep.GOT_DATA_IN, WEEK, GO_LIVE.minusDays(3), false));        // lost nowhere

        var steps = ActivationFunnelService.reached(people, GO_LIVE).steps();

        assertThat(steps).extracting("step", "lost", "lostBeforeFunnelRecord").containsExactly(
                tuple(ReachedStep.SIGNED_UP, 0L, 0L),
                tuple(ReachedStep.OPENED_IMPORT, 2L, 1L),
                tuple(ReachedStep.CHOSE_FILE, 0L, 0L),
                tuple(ReachedStep.FILE_READ, 1L, 0L),
                tuple(ReachedStep.REVIEW_OPENED, 1L, 1L),
                tuple(ReachedStep.GOT_DATA_IN, 0L, 0L));
    }

    @Test
    void withNoFunnelRecordYet_everyDropIntoARecordStepIsBeforeIt() {
        var steps = ActivationFunnelService.reached(many(FunnelStep.SIGNED_UP, 3), null).steps();

        assertThat(steps.get(1).lostBeforeFunnelRecord()).isEqualTo(3);
    }

    @Test
    void weeks_noSignups_noRows() {
        assertThat(ActivationFunnelService.weeks(List.of(), WEEK)).isEmpty();
    }

    /** A week with no events, between two signups and up to this week, is a row of zeros. */
    @Test
    void weeks_quietWeeksUpToThisWeekAreRowsOfZeros() {
        LocalDate later = WEEK.plusWeeks(2);
        var rows = ActivationFunnelService.weeks(List.of(
                user(FunnelStep.GOT_DATA_IN, WEEK, WEEK, true),
                user(FunnelStep.SIGNED_UP, WEEK, WEEK, false),
                user(FunnelStep.CHOSE_FILE, later, later, false)), later.plusWeeks(1).plusDays(4));

        assertThat(rows).extracting("weekStart").containsExactly(WEEK, WEEK.plusWeeks(1), later, later.plusWeeks(1));
        var first = rows.get(0);
        assertThat(first.counts().get(FunnelStep.GOT_DATA_IN)).isEqualTo(1);
        assertThat(first.reached()).containsEntry(ReachedStep.SIGNED_UP, 2L).containsEntry(ReachedStep.GOT_DATA_IN, 1L);
        assertThat(first.cameBackLaterDay()).isEqualTo(1);
        for (var quiet : List.of(rows.get(1), rows.get(3))) {
            assertThat(quiet.counts().values()).containsOnly(0L);
            assertThat(quiet.reached()).hasSize(ReachedStep.values().length);
            assertThat(quiet.reached().values()).containsOnly(0L);
            assertThat(quiet.cameBackLaterDay()).isZero();
        }
        assertThat(rows.get(2).reached()).isEqualTo(Map.of(ReachedStep.SIGNED_UP, 1L, ReachedStep.OPENED_IMPORT, 1L,
                ReachedStep.CHOSE_FILE, 1L, ReachedStep.FILE_READ, 0L, ReachedStep.REVIEW_OPENED, 0L,
                ReachedStep.GOT_DATA_IN, 0L));
    }

    /** Signups dated after today (the shared IT database has some) end the rows at their own week. */
    @Test
    void weeks_aSignupWeekAfterTodayIsTheLastRow() {
        var rows = ActivationFunnelService.weeks(List.of(user(FunnelStep.SIGNED_UP)), WEEK.minusWeeks(1));

        assertThat(rows).extracting("weekStart").containsExactly(WEEK);
    }

    /** The weekly reached counts sum to the overall ones: the same people, split by week. */
    @Test
    void weeklyReachedSumsToTheOverallReached() {
        List<ActivationFunnelService.Counted> people = new ArrayList<>(many(FunnelStep.REFUSED, 2));
        people.add(user(FunnelStep.GOT_DATA_IN, WEEK.plusWeeks(1), WEEK.plusWeeks(1), false));
        people.add(user(FunnelStep.OPENED_IMPORT, WEEK.plusWeeks(3), WEEK.plusWeeks(3), false));

        var rows = ActivationFunnelService.weeks(people, WEEK.plusWeeks(3));
        var overall = ActivationFunnelService.reached(people, GO_LIVE);

        for (var row : overall.steps()) {
            long summed = rows.stream().mapToLong(w -> w.reached().get(row.step())).sum();
            assertThat(summed).as(row.step().name()).isEqualTo(row.reached());
        }
    }
}
