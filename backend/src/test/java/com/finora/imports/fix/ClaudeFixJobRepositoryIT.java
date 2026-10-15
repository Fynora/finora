package com.finora.imports.fix;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fix job record (V274): one row per held job, gone with the job, counted by its first send. */
class ClaudeFixJobRepositoryIT extends AbstractIntegrationTest {

    @Autowired private ClaudeFixJobRepository repository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;

    private ImportJob heldJob() {
        User owner = new User();
        owner.setEmail("claude-fix-it-" + UUID.randomUUID() + "@example.com");
        owner.setPasswordHash("irrelevant-for-this-test");
        owner.setFullName("Claude Fix IT User");
        owner.setRole("USER");
        owner.setPhoneVerified(true);
        owner = userRepository.save(owner);
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", "hash-" + UUID.randomUUID(),
                "key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    @Test
    void aJobRowGoesWithItsImportJob() {
        ImportJob job = heldJob();
        repository.save(ClaudeFixJob.sent(job.getId(), "abc123", Instant.now()));
        assertThat(repository.findById(job.getId())).isPresent();

        importJobRepository.deleteById(job.getId());

        assertThat(repository.findById(job.getId())).isEmpty();
    }

    @Test
    void theMonthlyCountCountsOnlySentRowsInTheWindow() {
        // A window far in the past, so rows other tests leave in the shared table never fall in it.
        Instant from = Instant.parse("2001-03-01T00:00:00Z");
        Instant to = Instant.parse("2001-04-01T00:00:00Z");
        repository.save(ClaudeFixJob.sent(heldJob().getId(), "a", from));
        repository.save(ClaudeFixJob.sent(heldJob().getId(), "b", Instant.parse("2001-03-31T23:59:59Z")));
        repository.save(ClaudeFixJob.sent(heldJob().getId(), "c", to));
        repository.save(ClaudeFixJob.sent(heldJob().getId(), "d", Instant.parse("2001-02-28T23:59:59Z")));
        repository.save(ClaudeFixJob.notStarted(heldJob().getId(), ClaudeFixJob.Reason.DIFFERS,
                Instant.parse("2001-03-15T00:00:00Z")));

        assertThat(repository.countStartedBetween(from, to)).isEqualTo(2);
    }

    @Test
    void aSecondRowForTheSameJobIsRefused() {
        ImportJob job = heldJob();
        repository.saveAndFlush(ClaudeFixJob.sent(job.getId(), "first", Instant.now()));

        assertThatThrownBy(() -> repository.saveAndFlush(
                ClaudeFixJob.notStarted(job.getId(), ClaudeFixJob.Reason.OFF, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findById(job.getId())).get()
                .extracting(ClaudeFixJob::getState, ClaudeFixJob::getSentCommitSha)
                .containsExactly(ClaudeFixJob.State.SENT, "first");
    }

    @Test
    void aLoadedRowSavesAsAnUpdate() {
        ImportJob job = heldJob();
        repository.saveAndFlush(ClaudeFixJob.sent(job.getId(), "first", Instant.now()));
        ClaudeFixJob loaded = repository.findById(job.getId()).orElseThrow();
        loaded.prOpened(12, 3, Instant.now());
        repository.saveAndFlush(loaded);

        assertThat(repository.findById(job.getId())).get()
                .extracting(ClaudeFixJob::getState, ClaudeFixJob::getPublicPr, ClaudeFixJob::getPrivatePr)
                .containsExactly(ClaudeFixJob.State.PR_OPEN, 12, 3);
        assertThat(repository.findByStateIn(ClaudeFixJob.OPEN)).extracting(ClaudeFixJob::getJobId)
                .contains(job.getId());
    }

    @Test
    void theCapAlertIsClaimedOncePerMonth() {
        // The container is fresh for each run, and no other test claims a month this far back.
        assertThat(repository.claimCapAlert("1999-11", Instant.now())).isEqualTo(1);
        assertThat(repository.claimCapAlert("1999-11", Instant.now())).isZero();
        assertThat(repository.claimCapAlert("1999-12", Instant.now())).isEqualTo(1);
    }

    @Test
    void illegalTransitionsThrow() {
        Instant now = Instant.now();
        ClaudeFixJob sent = ClaudeFixJob.sent(UUID.randomUUID(), "s", now);
        assertThatThrownBy(() -> sent.fixed(now)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sent.rereading(now)).isInstanceOf(IllegalStateException.class);

        ClaudeFixJob notStarted = ClaudeFixJob.notStarted(UUID.randomUUID(), ClaudeFixJob.Reason.OFF, now);
        assertThatThrownBy(() -> notStarted.gaveUp(ClaudeFixJob.Reason.NO_PR, now))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notStarted.resent("x", now)).isInstanceOf(IllegalStateException.class);

        sent.resent("t", now);
        assertThat(sent.getAttempt()).isEqualTo(2);
        assertThatThrownBy(() -> sent.resent("u", now)).isInstanceOf(IllegalStateException.class);

        sent.prOpened(1, 2, now);
        assertThatThrownBy(() -> sent.prOpened(1, 2, now)).isInstanceOf(IllegalStateException.class);
        sent.rereading(now);
        sent.fixed(now);
        assertThatThrownBy(() -> sent.gaveUp(ClaudeFixJob.Reason.STILL_HELD, now))
                .isInstanceOf(IllegalStateException.class);
    }
}
