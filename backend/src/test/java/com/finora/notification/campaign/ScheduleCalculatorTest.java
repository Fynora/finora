package com.finora.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** Pure schedule arithmetic: the window, the 2-hour late rule and the day after a run, all IST. */
class ScheduleCalculatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalTime SEVEN_PM = LocalTime.of(19, 0);

    private static Instant ist(LocalDate date, int hour, int minute) {
        return IstClock.at(date, LocalTime.of(hour, minute));
    }

    // ---- the 07:00-21:59 window

    @Test
    void windowIncludesSevenAmAndTwentyOneFiftyNineButNotTwentyTwo() {
        assertThat(ScheduleCalculator.isInsideWindow(LocalTime.of(6, 59))).isFalse();
        assertThat(ScheduleCalculator.isInsideWindow(LocalTime.of(7, 0))).isTrue();
        assertThat(ScheduleCalculator.isInsideWindow(LocalTime.of(21, 59))).isTrue();
        assertThat(ScheduleCalculator.isInsideWindow(LocalTime.of(21, 59, 59))).isTrue();
        assertThat(ScheduleCalculator.isInsideWindow(LocalTime.of(22, 0))).isFalse();
        assertThat(ScheduleCalculator.isInsideWindow(LocalTime.MIDNIGHT)).isFalse();
    }

    // ---- next daily slot

    @Test
    void nextDailySlotIsTodayWhenTheTimeHasNotYetPassed() {
        Instant next = ScheduleCalculator.nextDailySlotAfter(SEVEN_PM, ist(DAY, 15, 0));
        assertThat(next).isEqualTo(ist(DAY, 19, 0));
    }

    @Test
    void nextDailySlotIsTomorrowWhenTheTimeHasPassed() {
        Instant next = ScheduleCalculator.nextDailySlotAfter(SEVEN_PM, ist(DAY, 20, 0));
        assertThat(next).isEqualTo(ist(DAY.plusDays(1), 19, 0));
    }

    @Test
    void nextDailySlotIsStrictlyAfter_exactlyAtTheSlotMeansTomorrow() {
        Instant next = ScheduleCalculator.nextDailySlotAfter(SEVEN_PM, ist(DAY, 19, 0));
        assertThat(next).isEqualTo(ist(DAY.plusDays(1), 19, 0));
    }

    @Test
    void theDayIsTheIstDayNotTheUtcDay() {
        // 00:30 IST on the 7th is 19:00 UTC on the 6th: still "the 7th" for a campaign.
        Instant afterMidnightIst = IstClock.at(DAY.plusDays(1), LocalTime.of(0, 30));
        Instant next = ScheduleCalculator.nextDailySlotAfter(SEVEN_PM, afterMidnightIst);
        assertThat(next).isEqualTo(ist(DAY.plusDays(1), 19, 0));
    }

    @Test
    void slotAfterARunIsTomorrowEvenWhenTheRunWasLate() {
        Instant slot = ist(DAY, 19, 0);
        assertThat(ScheduleCalculator.slotAfterRunOf(slot, SEVEN_PM)).isEqualTo(ist(DAY.plusDays(1), 19, 0));
    }

    @Test
    void endDateIsTheLastDayASendMayHappenOn() {
        LocalDate endsOn = DAY;
        assertThat(ScheduleCalculator.isPastEnd(ist(DAY, 19, 0), endsOn)).isFalse();
        assertThat(ScheduleCalculator.isPastEnd(ist(DAY.plusDays(1), 19, 0), endsOn)).isTrue();
        assertThat(ScheduleCalculator.isPastEnd(ist(DAY.plusDays(400), 19, 0), null)).isFalse();
    }

    // ---- the missed-run rule

    @Test
    void anOnTimeRunIsSent() {
        assertThat(ScheduleCalculator.decide(ist(DAY, 19, 0), ist(DAY, 19, 0)).send()).isTrue();
    }

    @Test
    void recoveryFortyMinutesLateIsSent() {
        assertThat(ScheduleCalculator.decide(ist(DAY, 19, 0), ist(DAY, 19, 40)).send()).isTrue();
    }

    @Test
    void recoveryAtOneHourFiftyNineIsSent() {
        Instant slot = ist(DAY, 19, 0);
        assertThat(ScheduleCalculator.decide(slot, slot.plus(Duration.ofMinutes(119))).send()).isTrue();
    }

    @Test
    void exactlyTwoHoursLateIsStillSent() {
        // 19:00 slot, 21:00 now: 2h00m late and inside the window.
        Instant slot = ist(DAY, 19, 0);
        assertThat(ScheduleCalculator.decide(slot, slot.plus(Duration.ofHours(2))).send()).isTrue();
    }

    @Test
    void twoHoursAndOneMinuteLateIsMissed() {
        Instant slot = ist(DAY, 19, 0);
        ScheduleCalculator.Decision decision =
                ScheduleCalculator.decide(slot, slot.plus(Duration.ofMinutes(121)));
        assertThat(decision.send()).isFalse();
        assertThat(decision.reason()).contains("2 hours");
    }

    @Test
    void recoveryAtThreeInTheMorningIsMissed() {
        Instant slot = ist(DAY, 19, 0);
        assertThat(ScheduleCalculator.decide(slot, ist(DAY.plusDays(1), 3, 0)).send()).isFalse();
    }

    @Test
    void aLateRunThatWouldLandAfterTheWindowClosesIsMissedEvenWithinTwoHours() {
        // 21:30 slot, outage ends 22:05: only 35 minutes late, but 22:05 is outside 07:00-21:59.
        ScheduleCalculator.Decision decision =
                ScheduleCalculator.decide(ist(DAY, 21, 30), ist(DAY, 22, 5));
        assertThat(decision.send()).isFalse();
        assertThat(decision.reason()).contains("07:00-21:59");
    }

    @Test
    void aLateRunThatLandsExactlyAtTheLastAllowedMinuteIsSent() {
        assertThat(ScheduleCalculator.decide(ist(DAY, 21, 0), ist(DAY, 21, 59)).send()).isTrue();
    }

    // ---- daily campaign + send now + outage (calendar interaction)

    @Test
    void sendNowThenOutageNextDayGivesMissedAndTheDayAfterNotTwoDaysLate() {
        // Daily 19:00. Admin presses send now at 15:00 on DAY: the slot for DAY is consumed, so the
        // next slot is DAY+1 19:00.
        Instant dueSlot = ist(DAY, 19, 0);
        Instant afterSendNow = ScheduleCalculator.slotAfterRunOf(dueSlot, SEVEN_PM);
        assertThat(afterSendNow).isEqualTo(ist(DAY.plusDays(1), 19, 0));

        // Outage DAY+1 18:00-22:30: the scheduler gets to the slot at 22:30.
        Instant recovery = ist(DAY.plusDays(1), 22, 30);
        assertThat(ScheduleCalculator.decide(afterSendNow, recovery).send()).isFalse();

        // Next slot: the day after, 19:00 -- not sent two days late, not scheduled in the past.
        Instant tomorrow = ScheduleCalculator.slotAfterRunOf(afterSendNow, SEVEN_PM);
        Instant afterNow = ScheduleCalculator.nextDailySlotAfter(SEVEN_PM, recovery);
        assertThat(tomorrow).isEqualTo(afterNow).isEqualTo(ist(DAY.plusDays(2), 19, 0));
    }

    @Test
    void aLongOutageSchedulesTheNextFutureSlotNotAStaleOne() {
        Instant slot = ist(DAY, 19, 0);
        Instant now = ist(DAY.plusDays(3), 10, 0); // down for three days
        Instant tomorrowOfSlot = ScheduleCalculator.slotAfterRunOf(slot, SEVEN_PM);
        Instant afterNow = ScheduleCalculator.nextDailySlotAfter(SEVEN_PM, now);
        assertThat(tomorrowOfSlot).isBefore(now);
        assertThat(afterNow).isEqualTo(ist(DAY.plusDays(3), 19, 0));
    }
}
