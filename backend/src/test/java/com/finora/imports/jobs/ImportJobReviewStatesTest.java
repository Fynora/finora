package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule itself, on fixed instants -- the one thing {@code ImportJobReviewStateIT} cannot pin with
 * a real clock is the exact boundary of the expiry, because time moves between setting it and
 * asking.
 */
class ImportJobReviewStatesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T06:00:00Z");
    private static final String HASH = "a".repeat(64);

    private static ImportJob job() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.csv", HASH, "key", "CSV");
        ReflectionTestUtils.setField(job, "createdAt", NOW.minusSeconds(3_600));
        return job;
    }

    private static ImportSession session(String status, Instant expiresAt) {
        ImportSession session = new ImportSession();
        session.setStatus(status);
        session.setExpiresAt(expiresAt);
        return session;
    }

    private static ImportJobDto.ReviewState state(ImportSession session, List<ImportJobReviewStates.FileImport> imports) {
        return ImportJobReviewStates.stateOf(job(), session, imports, NOW);
    }

    @Test
    void openUntilTheInstantBeforeItsExpiry() {
        assertThat(state(session(ImportSession.STATUS_STAGED, NOW.plusMillis(1)), List.of()))
                .isEqualTo(ImportJobDto.ReviewState.OPEN);
    }

    /** {@code ImportSessionService.getOwnedSession} refuses a session whose expiry is not after now,
     *  so at the instant itself the review no longer opens. */
    @Test
    void expiredAtTheInstantOfItsExpiry() {
        assertThat(state(session(ImportSession.STATUS_STAGED, NOW), List.of()))
                .isEqualTo(ImportJobDto.ReviewState.EXPIRED);
    }

    @Test
    void expiredOnceItsRowsAreGone() {
        assertThat(state(null, List.of())).isEqualTo(ImportJobDto.ReviewState.EXPIRED);
    }

    @Test
    void importedWhenItsRowsWereConfirmed_evenPastTheirExpiry() {
        assertThat(state(session(ImportSession.STATUS_CONFIRMED, NOW.minusSeconds(60)), List.of()))
                .isEqualTo(ImportJobDto.ReviewState.IMPORTED);
    }

    @Test
    void importedWhenTheSameFileWasImportedAfterTheJob() {
        assertThat(state(null, List.of(new ImportJobReviewStates.FileImport(HASH, NOW.minusSeconds(60)))))
                .isEqualTo(ImportJobDto.ReviewState.IMPORTED);
    }

    /** An import of the same file from before this upload says nothing about this upload's rows. */
    @Test
    void anImportOfTheSameFileFromBeforeTheJobDoesNotCount() {
        assertThat(state(null, List.of(new ImportJobReviewStates.FileImport(HASH, NOW.minusSeconds(7_200)))))
                .isEqualTo(ImportJobDto.ReviewState.EXPIRED);
    }

    @Test
    void anImportOfADifferentFileDoesNotCount() {
        assertThat(state(null, List.of(new ImportJobReviewStates.FileImport("b".repeat(64), NOW.minusSeconds(60)))))
                .isEqualTo(ImportJobDto.ReviewState.EXPIRED);
    }

    /** The file answers even while the rows are still open: the statement is already in. */
    @Test
    void importedBeatsOpen() {
        assertThat(state(session(ImportSession.STATUS_STAGED, NOW.plusSeconds(3_600)),
                List.of(new ImportJobReviewStates.FileImport(HASH, NOW.minusSeconds(60)))))
                .isEqualTo(ImportJobDto.ReviewState.IMPORTED);
    }
}
