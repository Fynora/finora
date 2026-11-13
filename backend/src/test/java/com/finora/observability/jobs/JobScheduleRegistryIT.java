package com.finora.observability.jobs;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobScheduleRegistryIT extends AbstractIntegrationTest {

    @Autowired
    private JobScheduleRegistry registry;

    @Test
    void listsEveryCataloguedJob_withAUsableSchedule() {
        List<JobSchedule> all = registry.all();

        assertThat(all).extracting(JobSchedule::key).doesNotHaveDuplicates();
        assertThat(all).extracting(JobSchedule::key)
                .as("every production job the scheduler registered is catalogued")
                .allMatch(key -> BackgroundJobCatalog.lookup(key).isPresent() || key.startsWith("RecordingProbeJobs."));
        assertThat(all).extracting(JobSchedule::key).containsAll(BackgroundJobCatalog.keys());
        assertThat(all).allSatisfy(s -> {
            assertThat((s.interval() == null) != (s.cron() == null)).as(s.key() + " has one kind of schedule").isTrue();
            if (s.interval() != null) assertThat(s.interval()).isPositive();
            assertThat(s.description()).isNotBlank();
        });
        assertThat(registry.find("PhoneIdentitySweepService.scheduledSweep")).get()
                .extracting(JobSchedule::cron).isEqualTo("0 45 3 * * *");
        assertThat(registry.find("NoSuch.job")).isEmpty();
        assertThat(registry.appStartedAt()).isNotNull();
    }
}
