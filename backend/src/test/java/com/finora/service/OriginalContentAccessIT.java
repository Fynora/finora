package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.original.OriginalContent;
import com.finora.imports.passwords.StatementPasswordService;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each held queue hands over for an Admin's in-browser view of the real statement: audited with
 * the typed reason before the bytes are read, only for an item still held, and never a locked file.
 * The HTTP gates (roles, the reason's length, the view row) are {@code AdminOriginalViewIT}'s.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage",
        "app.statement-passwords.save.enabled=true"
})
class OriginalContentAccessIT extends AbstractIntegrationTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 original-content fixture bytes".getBytes(StandardCharsets.UTF_8);
    private static final String REASON = "Rows on page 2 are missing from the preview";
    private static final String PASSWORD = "open-sesame-original";
    private static final String WRONG_PASSWORD = "not-the-password";

    @Autowired private AdminHeldImportService heldImports;
    @Autowired private HeldStatementService heldStatements;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private StatementStorage storage;
    @Autowired private StatementPasswordService statementPasswordService;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("original-content-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Original Content IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A job in the state the parser-gap queue holds, as the removed download IT built it. */
    private ImportJob heldJob(byte[] content) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(content);
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private ImportJob heldJob() {
        return heldJob(PDF_BYTES);
    }

    private ImportJob heldJobWhoseStoredFileIsDeleted() {
        ImportJob job = heldJob();
        storage.delete(job.getObjectKey());
        return job;
    }

    /** A job still waiting for the worker: never in the triage queue. */
    private ImportJob queuedJob() {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        return importJobRepository.save(
                new ImportJob(owner.getId(), "statement.pdf", address.hash(), address.key(), "PDF"));
    }

    /** A protected synthetic PDF whose saved password is the wrong one. */
    private ImportJob heldJobWithAWrongSavedPassword() {
        byte[] locked;
        try {
            locked = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildSampleWithPlantedCustomerData(), PASSWORD);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ImportJob job = heldJob(locked);
        statementPasswordService.saveForJob(job.getUserId(), job.getId(), WRONG_PASSWORD);
        return job;
    }

    /** A job in the state the trust gate leaves one in, as the removed download IT built it. */
    private HeldStatement seedHold(String heldId) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);

        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                heldId, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    /** Staged through the synchronous endpoint, which keeps no password: the file cannot be opened. */
    private HeldStatement lockedHoldWithoutSavedPassword(String heldId) {
        HeldStatement held = seedHold(heldId);
        held.markLockedWithoutPassword();
        return heldStatementRepository.save(held);
    }

    // ------------------------------------------------------------------------- held imports

    @Test
    void anImportViewIsAuditedWithTheReasonBeforeTheRead() {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");

        OriginalContent content = heldImports.contentForOriginalView(admin.getId(), job.getId(), REASON);

        assertThat(content.content()).isEqualTo(PDF_BYTES);
        assertThat(content.queue()).isEqualTo("IMPORT");
        assertThat(content.itemRef()).isEqualTo(job.getId().toString());
        assertThat(content.format()).isEqualTo("PDF");
        assertThat(content.subjectUserId()).isEqualTo(job.getUserId());
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId())).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("HELD_IMPORT_ORIGINAL_VIEWED");
            assertThat(e.getUserId()).isEqualTo(admin.getId());
            assertThat(e.getMetadata()).containsEntry("reason", REASON)
                    .containsEntry("actorId", admin.getId().toString())
                    .containsEntry("subjectUserId", job.getUserId().toString())
                    .containsEntry("savedPasswordOnFile", false);
        });
    }

    @Test
    void aMissingFileIsAConflictAndTheAttemptIsStillAudited() {
        ImportJob job = heldJobWhoseStoredFileIsDeleted();
        User admin = createUser("ADMIN");

        assertThatThrownBy(() -> heldImports.contentForOriginalView(admin.getId(), job.getId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .extracting(AuditLog::getAction).contains("HELD_IMPORT_ORIGINAL_VIEWED");
    }

    @Test
    void aJobThatIsNotHeldIsRefusedWithNoAudit() {
        ImportJob queued = queuedJob();
        User admin = createUser("ADMIN");

        assertThatThrownBy(() -> heldImports.contentForOriginalView(admin.getId(), queued.getId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(queued.getId()))
                .extracting(AuditLog::getAction).doesNotContain("HELD_IMPORT_ORIGINAL_VIEWED");
    }

    @Test
    void anOpenViewStopsOnceTheJobIsReleased() {
        ImportJob job = heldJob();
        assertThat(heldImports.contentForOpenView(job.getId()).content()).isEqualTo(PDF_BYTES);

        heldImports.resolve(createUser("ADMIN").getId(), job.getId(), "We fixed the reader; please re-upload.");

        assertThatThrownBy(() -> heldImports.contentForOpenView(job.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anOpenViewFetchWritesNoAuditEntry() {
        ImportJob job = heldJob();

        heldImports.contentForOpenView(job.getId());

        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId())).isEmpty();
    }

    @Test
    void aFailedUnlockStillLeavesTheAuditButNoView() {
        ImportJob job = heldJobWithAWrongSavedPassword();
        User admin = createUser("ADMIN");

        assertThatThrownBy(() -> heldImports.contentForOriginalView(admin.getId(), job.getId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("The saved password did not open the statement behind held import "
                            + job.getId() + ", so it cannot be shown.");
                });
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId())).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("HELD_IMPORT_ORIGINAL_VIEWED");
            assertThat(e.getMetadata()).containsEntry("savedPasswordOnFile", true);
        });
    }

    // ------------------------------------------------------------------------- held statements

    @Test
    void aTrustViewIsAuditedWithTheReasonAndHeldIdBeforeTheRead() {
        HeldStatement held = seedHold("HLD-2026-710001");
        User admin = createUser("ADMIN");

        OriginalContent content = heldStatements.contentForOriginalView(admin.getId(), held.getHeldId(), REASON);

        assertThat(content.content()).isEqualTo(PDF_BYTES);
        assertThat(content.queue()).isEqualTo("TRUST");
        assertThat(content.itemRef()).isEqualTo("HLD-2026-710001");
        assertThat(content.jobId()).isEqualTo(held.getImportJobId());
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId())).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("TRUST_REVIEW_ORIGINAL_VIEWED");
            assertThat(e.getUserId()).isEqualTo(admin.getId());
            assertThat(e.getMetadata()).containsEntry("reason", REASON).containsEntry("heldId", "HLD-2026-710001");
        });
    }

    @Test
    void aFailedTrustReadIsStillAudited() {
        HeldStatement held = seedHold("HLD-2026-710002");
        storage.delete(importJobRepository.findById(held.getImportJobId()).orElseThrow().getObjectKey());
        User admin = createUser("ADMIN");

        assertThatThrownBy(() -> heldStatements.contentForOriginalView(admin.getId(), held.getHeldId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .extracting(AuditLog::getAction).contains("TRUST_REVIEW_ORIGINAL_VIEWED");
    }

    @Test
    void aLockedTrustHoldIsRefusedBeforeTheAudit() {
        HeldStatement held = lockedHoldWithoutSavedPassword("HLD-2026-710003");
        User admin = createUser("ADMIN");

        assertThatThrownBy(() -> heldStatements.contentForOriginalView(admin.getId(), held.getHeldId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .extracting(AuditLog::getAction).doesNotContain("TRUST_REVIEW_ORIGINAL_VIEWED");
    }

    @Test
    void aDecidedHoldIsRefusedBeforeTheAuditAndEndsAnOpenView() {
        HeldStatement held = seedHold("HLD-2026-710004");
        User admin = createUser("ADMIN");
        assertThat(heldStatements.contentForOpenView(held.getHeldId()).content()).isEqualTo(PDF_BYTES);

        held.reject(admin.getId(), Instant.now());
        heldStatementRepository.save(held);

        assertThatThrownBy(() -> heldStatements.contentForOpenView(held.getHeldId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> heldStatements.contentForOriginalView(admin.getId(), held.getHeldId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .extracting(AuditLog::getAction).doesNotContain("TRUST_REVIEW_ORIGINAL_VIEWED");
    }

    @Test
    void anUnknownHeldIdIsNotFound() {
        assertThatThrownBy(() -> heldStatements.contentForOriginalView(createUser("ADMIN").getId(), "HLD-2026-799999", REASON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
