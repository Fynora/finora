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
        List<JobSchedule> all = registry.all().stream()
                .filter(s -> s.source() == JobSchedule.Source.BACKEND).toList();

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

    @Test
    void listsTheTenGitHubWorkflowsAfterTheBackendJobs() {
        List<JobSchedule> github = registry.all().stream().filter(s -> s.source() == JobSchedule.Source.GITHUB).toList();
        assertThat(github).hasSize(10);
        assertThat(github).extracting(JobSchedule::key).allMatch(k -> k.startsWith("github:"));
        assertThat(registry.find("github:Fynora/finora/github-traffic-metrics.yml")).get()
                .extracting(JobSchedule::offReason).isEqualTo("Needs a GitHub token from Sid");
        assertThat(registry.find("github:Fynora/finora-statement-traces/traces.yml")).get()
                .extracting(JobSchedule::description).isEqualTo("daily at 04:30 UTC");
        assertThat(registry.all().stream().filter(s -> s.source() == JobSchedule.Source.BACKEND)).isNotEmpty();
    }
}
