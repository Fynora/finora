package com.finora.observability.jobs;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JobScheduleWordingTest {

    @Test
    void intervalsReadInTheirLargestWholeUnit() {
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofSeconds(30))).isEqualTo("every 30 seconds");
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofSeconds(1))).isEqualTo("every second");
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofMinutes(1))).isEqualTo("every minute");
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofMinutes(15))).isEqualTo("every 15 minutes");
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofHours(1))).isEqualTo("every hour");
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofHours(6))).isEqualTo("every 6 hours");
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofDays(1))).isEqualTo("every day");
        assertThat(JobScheduleRegistry.describeInterval(Duration.ofMillis(1500))).isEqualTo("every 1500 ms");
    }

    @Test
    void aDailyCronReadsAsItsTime_otherCronsAsWritten() {
        assertThat(JobScheduleRegistry.describeCron("0 45 3 * * *")).isEqualTo("daily at 03:45 (server time)");
        assertThat(JobScheduleRegistry.describeCron("0 0 */2 * * *")).isEqualTo("cron 0 0 */2 * * *");
    }

    @Test
    void theKeyComesFromTheTasksMethodName() {
        assertThat(JobScheduleRegistry.keyFromTaskName("com.finora.service.AccountPurgeSweepService.scheduledSweep"))
                .isEqualTo("AccountPurgeSweepService.scheduledSweep");
        assertThat(JobScheduleRegistry.keyFromTaskName("nonsense")).isNull();
    }
}
