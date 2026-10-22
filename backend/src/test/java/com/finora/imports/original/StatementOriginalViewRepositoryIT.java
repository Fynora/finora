package com.finora.imports.original;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The record of each opening: ordered by when it happened, live for one viewer for 30 minutes,
 *  and gone with its import job. */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage"
})
class StatementOriginalViewRepositoryIT extends AbstractIntegrationTest {

    @Autowired private StatementOriginalViewRepository views;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StatementStorage storage;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final byte[] PDF_BYTES = "%PDF-1.4 original-view fixture bytes".getBytes(StandardCharsets.UTF_8);

    private User createUser(String role) {
        User user = new User();
        user.setEmail("original-view-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Original View IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A job in the state the parser-gap queue holds, as {@code AdminHeldImportDownloadIT} built it. */
    private ImportJob heldJob() {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    @Test
    void aSavedViewComesBackInOpeningOrderAndGoesWithItsJob() {
        ImportJob job = heldJob();
        Instant t0 = Instant.parse("2026-10-15T05:00:00Z");
        views.save(StatementOriginalView.open(job.getId(), job.getUserId(), UUID.randomUUID(), "IMPORT",
                job.getId().toString(), "PDF", 3, t0.plusSeconds(60)));
        views.save(StatementOriginalView.open(job.getId(), job.getUserId(), UUID.randomUUID(), "IMPORT",
                job.getId().toString(), "PDF", 3, t0));

        assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId()))
                .extracting(StatementOriginalView::getOpenedAt)
                .containsExactly(t0, t0.plusSeconds(60));

        importJobRepository.delete(job);
        assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId())).isEmpty();
    }

    /**
     * The typed reason lives on the audit entry only, under the audit's own retention (details
     * redacted after app.audit.redaction.retention-days, 730 by default). A copy here would outlive
     * it: these rows last as long as the import job, which is until the account is deleted.
     */
    @Test
    void theTableKeepsNoCopyOfTheReason() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'statement_original_views'
                """, String.class);
        assertThat(columns).contains("viewer_id", "opened_at", "expires_at").doesNotContain("reason");
    }

    @Test
    void aViewIsLiveOnlyForItsViewerAndOnlyForThirtyMinutes() {
        UUID viewer = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-15T05:00:00Z");
        StatementOriginalView v = StatementOriginalView.open(UUID.randomUUID(), UUID.randomUUID(), viewer,
                "IMPORT", "x", "PDF", 1, now);

        assertThat(v.isLiveFor(viewer, now.plus(Duration.ofMinutes(29)))).isTrue();
        assertThat(v.isLiveFor(viewer, now.plus(Duration.ofMinutes(30)))).isFalse();
        assertThat(v.isLiveFor(UUID.randomUUID(), now)).isFalse();
    }
}
