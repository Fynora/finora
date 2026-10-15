package com.finora.imports.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The overdue email's words for every state and reason, and what the lookup returns with no row. */
class ClaudeFixStatusTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "OFF, not tried (it was off when this was held)",
            "DIFFERS, not tried (the blanked copy does not match)",
            "NOT_CHECKED, not tried (the blanked copy was not checked)",
            "REFUSED, not tried (the blanked copy was refused)",
            "CSV, not tried (the statement is a CSV)",
            "CAP_REACHED, not tried (monthly limit reached)",
            "ERROR, could not start"
    })
    void notStartedRowsSayWhy(ClaudeFixJob.Reason reason, String line) {
        assertThat(ClaudeFixStatus.of(ClaudeFixJob.notStarted(UUID.randomUUID(), reason, NOW), 50).emailLine())
                .isEqualTo(line);
    }

    @Test
    void openAndFinishedRowsSayWhereTheyAre() {
        ClaudeFixJob job = ClaudeFixJob.sent(UUID.randomUUID(), "sha", NOW);
        assertThat(ClaudeFixStatus.of(job, 50).emailLine()).isEqualTo("working (attempt 1 of 2)");
        job.resent("sha2", NOW);
        assertThat(ClaudeFixStatus.of(job, 50).emailLine()).isEqualTo("working (attempt 2 of 2)");
        job.prOpened(41, 7, NOW);
        assertThat(ClaudeFixStatus.of(job, 50).emailLine()).isEqualTo("PR #41 opened");
        assertThat(ClaudeFixStatus.of(job, 50).linkedPublicPr()).isEqualTo(41);
        job.rereading(NOW);
        assertThat(ClaudeFixStatus.of(job, 50).emailLine()).isEqualTo("fix deployed, re-reading");
        assertThat(ClaudeFixStatus.of(job, 50).linkedPublicPr()).isNull();
        job.fixed(NOW);
        assertThat(ClaudeFixStatus.of(job, 50).emailLine()).isEqualTo("fixed by PR #41");
    }

    @ParameterizedTest
    @CsvSource({
            "RUN_FAILED, 2, gave up after 2 attempts",
            "NO_PR, 2, gave up after 2 attempts",
            "NO_PR, 1, gave up after 1 attempt",
            "STILL_HELD, 1, 'fix deployed, statement still held'",
            "DECIDED, 1, 'stopped, a person decided this hold'"
    })
    void gaveUpRowsSayWhy(ClaudeFixJob.Reason reason, int attempt, String line) {
        ClaudeFixJob job = ClaudeFixJob.sent(UUID.randomUUID(), "sha", NOW);
        if (attempt == 2) job.resent("sha2", NOW);
        job.gaveUp(reason, NOW);
        assertThat(ClaudeFixStatus.of(job, 50).emailLine()).isEqualTo(line);
    }

    @Test
    void theLookupSaysOffWithNoRowOnlyWhileTheSwitchIsOff() {
        ClaudeFixJobRepository jobs = mock(ClaudeFixJobRepository.class);
        when(jobs.findById(any())).thenReturn(Optional.empty());
        ClaudeFixProperties properties = new ClaudeFixProperties();
        ClaudeFixStatusLookup lookup = new ClaudeFixStatusLookup(jobs, properties);

        assertThat(lookup.forJob(UUID.randomUUID()).state()).isEqualTo("OFF");
        properties.setEnabled(true);
        assertThat(lookup.forJob(UUID.randomUUID())).isNull();
    }

    @Test
    void aHoldRecordedWhileOffSaysSoOnceTheSwitchIsOn() {
        ClaudeFixJobRepository jobs = mock(ClaudeFixJobRepository.class);
        UUID id = UUID.randomUUID();
        when(jobs.findById(id)).thenReturn(Optional.of(ClaudeFixJob.notStarted(id, ClaudeFixJob.Reason.OFF, NOW)));
        ClaudeFixProperties properties = new ClaudeFixProperties();
        ClaudeFixStatusLookup lookup = new ClaudeFixStatusLookup(jobs, properties);

        assertThat(lookup.forJob(id).state()).isEqualTo("OFF");
        properties.setEnabled(true);
        assertThat(lookup.forJob(id).state()).isEqualTo("NOT_STARTED");
        assertThat(lookup.forJob(id).reason()).isEqualTo("OFF");
        assertThat(lookup.forJob(id).emailLine()).isEqualTo("not tried (it was off when this was held)");
    }
}
