package com.finora.observability.jobs;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real scheduled methods, run by the real scheduler, recorded through Spring's scheduled-task
 * observation: proves the hook sees every run without the jobs knowing about it, and that a job's
 * own report turns a normal return into "partly failed" or "off".
 */
class BackgroundJobRecordingIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        RecordingProbeJobs recordingProbeJobs() {
            return new RecordingProbeJobs();
        }
    }

    static class RecordingProbeJobs {
        @Scheduled(fixedDelay = 200)
        public void fine() {
            BackgroundJobReport.handled(3);
        }

        @Scheduled(fixedDelay = 200)
        public void twoItemsFail() {
            BackgroundJobReport.handled(10);
            BackgroundJobReport.itemFailed(new IllegalStateException("row for someone@example.com"));
            BackgroundJobReport.itemFailed(new IllegalStateException("another"));
        }

        @Scheduled(fixedDelay = 200)
        public void crashes() {
            throw new IllegalArgumentException("crash for someone@example.com");
        }

        @Scheduled(fixedDelay = 200)
        public void switchedOff() {
            BackgroundJobReport.switchedOff();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyRunIsRecordedWithItsOutcome() throws InterruptedException {
        Map<String, Object> fine = awaitFinished("RecordingProbeJobs.fine");
        Map<String, Object> partly = awaitFinished("RecordingProbeJobs.twoItemsFail");
        Map<String, Object> crashed = awaitFinished("RecordingProbeJobs.crashes");
        Map<String, Object> off = awaitFinished("RecordingProbeJobs.switchedOff");

        assertThat(fine.get("last_outcome")).isEqualTo("OK");
        assertThat(fine.get("last_handled")).isEqualTo(3);
        assertThat(fine.get("last_success_at")).isNotNull();
        assertThat(fine.get("consecutive_failures")).isEqualTo(0);
        assertThat(fine.get("broken_since")).isNull();

        assertThat(partly.get("last_outcome")).isEqualTo("PARTLY_FAILED");
        assertThat(partly.get("last_failed_items")).isEqualTo(2);
        assertThat(partly.get("last_handled")).isEqualTo(10);
        assertThat((Integer) partly.get("consecutive_failures")).isPositive();
        assertThat(partly.get("broken_since")).isNotNull();

        assertThat(crashed.get("last_outcome")).isEqualTo("CRASHED");
        assertThat(crashed.get("last_success_at")).isNull();

        assertThat(off.get("last_outcome")).isEqualTo("OFF");
        assertThat(off.get("last_success_at")).isNotNull();
        assertThat(off.get("broken_since")).isNull();

        assertThat(fine.get("last_instance")).isNotNull();
    }

    @Test
    void onlyFailedRunsAddFailureRows_withTypeAndPlaceOnly() throws InterruptedException {
        awaitFinished("RecordingProbeJobs.crashes");
        awaitFinished("RecordingProbeJobs.twoItemsFail");
        awaitFinished("RecordingProbeJobs.fine");
        awaitFinished("RecordingProbeJobs.switchedOff");

        assertThat(failureCount("RecordingProbeJobs.fine")).isZero();
        assertThat(failureCount("RecordingProbeJobs.switchedOff")).isZero();

        List<String> crashSummaries = jdbc.queryForList(
                "SELECT error_summary FROM background_job_failures WHERE job_key = ?", String.class,
                "RecordingProbeJobs.crashes");
        assertThat(crashSummaries).isNotEmpty()
                .allSatisfy(s -> assertThat(s).startsWith("IllegalArgumentException at BackgroundJobRecordingIT:")
                        .doesNotContain("@").doesNotContain("someone"));

        List<Map<String, Object>> partlyRows = jdbc.queryForList(
                "SELECT outcome, failed_items, error_summary FROM background_job_failures WHERE job_key = ?",
                "RecordingProbeJobs.twoItemsFail");
        assertThat(partlyRows).isNotEmpty().allSatisfy(row -> {
            assertThat(row.get("outcome")).isEqualTo("PARTLY_FAILED");
            assertThat(row.get("failed_items")).isEqualTo(2);
            assertThat((String) row.get("error_summary")).doesNotContain("someone");
        });
    }

    private int failureCount(String key) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM background_job_failures WHERE job_key = ?",
                Integer.class, key);
        return n == null ? 0 : n;
    }

    private Map<String, Object> awaitFinished(String key) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM background_job_status WHERE job_key = ? AND last_finished_at IS NOT NULL", key);
            if (!rows.isEmpty()) return rows.get(0);
            Thread.sleep(100);
        }
        throw new AssertionError("no finished run recorded for " + key + " within 10 s");
    }
}
