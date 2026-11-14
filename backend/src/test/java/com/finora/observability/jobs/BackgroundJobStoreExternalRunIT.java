package com.finora.observability.jobs;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A GitHub run is recorded once, however many polls see it. */
class BackgroundJobStoreExternalRunIT extends AbstractIntegrationTest {

    private static final String KEY = "github:Fynora/finora/store-it.yml";

    @Autowired private BackgroundJobStore store;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM background_job_failures WHERE job_key = ?", KEY);
        jdbc.update("DELETE FROM background_job_status WHERE job_key = ?", KEY);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM background_job_status WHERE job_key = ?", KEY);
    }

    private int failures() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM background_job_failures WHERE job_key = ?", Integer.class, KEY);
        return n == null ? 0 : n;
    }

    @Test
    void aRunIsWrittenOnce() {
        Instant start = Instant.parse("2026-11-14T03:00:00Z");
        Instant end = start.plusSeconds(600);

        assertThat(store.recordExternalRun(KEY, 101L, "https://example.test/runs/101", start, end,
                JobOutcome.CRASHED, "Failed: Run tests")).isTrue();
        assertThat(store.recordExternalRun(KEY, 101L, "https://example.test/runs/101", start, end,
                JobOutcome.CRASHED, "Failed: Run tests")).isFalse();

        assertThat(failures()).isEqualTo(1);
        assertThat(row().get("last_outcome")).isEqualTo("CRASHED");
        assertThat(row().get("consecutive_failures")).isEqualTo(1);
        assertThat(row().get("last_external_run_id")).isEqualTo(101L);
        assertThat(row().get("last_external_url")).isEqualTo("https://example.test/runs/101");
    }

    @Test
    void aRunningRunThenItsFinishIsWritten() {
        Instant start = Instant.parse("2026-11-14T03:00:00Z");
        assertThat(store.recordExternalRun(KEY, 202L, "u", start, null, null, null)).isTrue();
        assertThat(row().get("last_finished_at")).isNull();
        assertThat(row().get("last_started_at")).isNotNull();
        assertThat(store.recordExternalRun(KEY, 202L, "u", start, null, null, null)).isFalse();

        assertThat(store.recordExternalRun(KEY, 202L, "u", start, start.plusSeconds(60), JobOutcome.OK, null)).isTrue();
        assertThat(row().get("last_outcome")).isEqualTo("OK");
        assertThat(row().get("last_finished_at")).isNotNull();
    }

    @Test
    void aNewRunReplacesTheOld_andSuccessClearsTheStreak() {
        Instant day1 = Instant.parse("2026-11-13T03:00:00Z");
        Instant day2 = Instant.parse("2026-11-14T03:00:00Z");
        store.recordExternalRun(KEY, 301L, "u1", day1, day1.plusSeconds(60), JobOutcome.CRASHED, "Failed: X");

        assertThat(store.recordExternalRun(KEY, 302L, "u2", day2, day2.plusSeconds(60), JobOutcome.OK, null)).isTrue();

        assertThat(row().get("last_outcome")).isEqualTo("OK");
        assertThat(row().get("consecutive_failures")).isEqualTo(0);
        assertThat(row().get("broken_since")).isNull();
        assertThat(row().get("last_success_at")).isNotNull();
        assertThat(row().get("last_external_url")).isEqualTo("u2");
        assertThat(failures()).isEqualTo(1);
    }
}
