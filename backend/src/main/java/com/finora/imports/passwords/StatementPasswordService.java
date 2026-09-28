package com.finora.imports.passwords;

import com.finora.entity.ImportJob;
import com.finora.entity.StatementPassword;
import com.finora.exception.ApiException;
import com.finora.repository.StatementPasswordRepository;
import com.finora.security.crypto.EncryptionException;
import com.finora.security.crypto.EncryptionService;
import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Protected-PDF passwords the user agreed to let Fynora keep (statement refresh, step 4).
 *
 * <p>Why keep one at all: a stored protected PDF cannot be opened again without it, so without
 * this a refresh, a re-import and the background dry run all have to stop and ask -- and a locked
 * upload cannot go through the import queue, which is what gives an upload the trust check and,
 * on a failure on our side, a place in the admin Held Imports queue.
 *
 * <p>Only with consent, per upload: the client asks, and a password arrives here only with the
 * user's yes. The password is encrypted with {@link EncryptionService} before it touches the
 * database, decrypted only to open that one file, and never logged or returned by any endpoint --
 * Settings lists which statements have one, never the value.
 *
 * <p>Off unless {@code app.statement-passwords.save.enabled}: the privacy policy that describes it
 * ships with the feature, not before.
 */
@Service
public class StatementPasswordService {

    private static final Logger log = LoggerFactory.getLogger(StatementPasswordService.class);

    /** What the user agreed to. Bump when the consent wording changes materially. */
    public static final String CONSENT_VERSION = "2026-09-statement-password-v1";

    private final StatementPasswordRepository repository;
    private final EncryptionService encryptionService;
    private final AuditService auditService;
    private final boolean enabled;

    public StatementPasswordService(StatementPasswordRepository repository,
                                    EncryptionService encryptionService,
                                    AuditService auditService,
                                    @Value("${app.statement-passwords.save.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.auditService = auditService;
        this.enabled = enabled;
    }

    /** Whether clients may offer to save a password. Reading and deleting saved ones works either way. */
    public boolean enabled() {
        return enabled;
    }

    // ---- queued uploads ------------------------------------------------------------------------

    /** Saves the password for a queued upload. The caller has already checked it opens the file. */
    @Transactional
    public void saveForJob(UUID userId, UUID importJobId, String password) {
        requireEnabled();
        var value = encryptionService.encrypt(password);
        Instant now = Instant.now();
        StatementPassword row = repository.findByImportJobId(importJobId)
                .map(existing -> { existing.replace(value, CONSENT_VERSION, now); return existing; })
                .orElseGet(() -> StatementPassword.forJob(userId, importJobId, value, CONSENT_VERSION, now));
        repository.save(row);
        auditService.record(userId, "STATEMENT_PASSWORD_SAVED", "ImportJob", importJobId,
                Map.of("consentVersion", CONSENT_VERSION));
    }

    /** The password a queued upload was accepted with, if the user saved one. */
    @Transactional(readOnly = true)
    public Optional<String> forJob(UUID importJobId) {
        return repository.findByImportJobId(importJobId).flatMap(this::decrypt);
    }

    /** The password the upload that staged this session was queued with, if the user saved one. */
    @Transactional(readOnly = true)
    public Optional<String> forSession(UUID importSessionId) {
        return repository.findHeldByJobsOfSession(importSessionId).stream().findFirst().flatMap(this::decrypt);
    }

    /**
     * Moves a confirmed upload's password onto the statements it produced. Called inside the
     * confirm's transaction, so the statements and their password commit together.
     */
    @Transactional
    public void carryToStatements(UUID importSessionId, Collection<UUID> statementImportIds) {
        if (importSessionId == null || statementImportIds.isEmpty()) return;
        List<StatementPassword> jobRows = repository.findHeldByJobsOfSession(importSessionId);
        if (jobRows.isEmpty()) return;
        StatementPassword source = jobRows.get(0);
        for (UUID statementId : statementImportIds) {
            repository.findByStatementImportId(statementId).ifPresent(repository::delete);
            repository.flush();
            repository.save(source.copyFor(statementId));
        }
        repository.deleteAll(jobRows);
    }

    /**
     * A held upload's file as a member of staff can read it: a protected PDF whose password the
     * user saved is unlocked in memory for this download only -- no unlocked copy is ever stored.
     * Anything else is returned as it is, and so is a file that cannot be unlocked: a held upload
     * is often one that failed to open at all, and the reviewer still needs the file itself.
     */
    @Transactional(readOnly = true)
    public ReviewCopy reviewCopy(ImportJob job, byte[] content) {
        if (!"PDF".equalsIgnoreCase(job.getSourceFormat())) return new ReviewCopy(content, false);
        Optional<String> password = forJob(job.getId());
        if (password.isEmpty()) return new ReviewCopy(content, false);
        try {
            return new ReviewCopy(com.finora.imports.pdf.PdfTextExtractor.unlockedCopy(content, password.get()), true);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Held upload {} could not be unlocked for review; handing over the stored file: {}",
                    job.getId(), e.getClass().getSimpleName());
            return new ReviewCopy(content, false);
        }
    }

    /** Whether a queued upload has a saved password -- known before its file is read. */
    @Transactional(readOnly = true)
    public boolean hasJobPassword(UUID importJobId) {
        return repository.findByImportJobId(importJobId).isPresent();
    }

    /** @param unlocked whether the saved password was used to remove the file's protection */
    public record ReviewCopy(byte[] content, boolean unlocked) {}

    // ---- stored statements ---------------------------------------------------------------------

    /** The password saved for a statement, if any. Owner-scoped: another user's id finds nothing. */
    @Transactional(readOnly = true)
    public Optional<String> forStatement(UUID userId, UUID statementImportId) {
        return repository.findByStatementImportId(statementImportId)
                .filter(p -> p.getUserId().equals(userId))
                .flatMap(this::decrypt);
    }

    /** Saves (or replaces) a statement's password. The caller has already opened the file with it. */
    @Transactional
    public void saveForStatement(UUID userId, UUID statementImportId, String password) {
        requireEnabled();
        var value = encryptionService.encrypt(password);
        Instant now = Instant.now();
        StatementPassword row = repository.findByStatementImportId(statementImportId)
                .map(existing -> { existing.replace(value, CONSENT_VERSION, now); return existing; })
                .orElseGet(() -> StatementPassword.forStatement(userId, statementImportId, value, CONSENT_VERSION, now));
        repository.save(row);
        auditService.record(userId, "STATEMENT_PASSWORD_SAVED", "StatementImport", statementImportId,
                Map.of("consentVersion", CONSENT_VERSION));
    }

    /** A re-import of a statement opens the same file, so it keeps the same saved password. */
    @Transactional
    public void copyToStatement(UUID fromStatementImportId, UUID toStatementImportId) {
        repository.findByStatementImportId(fromStatementImportId).ifPresent(source -> {
            if (repository.findByStatementImportId(toStatementImportId).isEmpty()) {
                repository.save(source.copyFor(toStatementImportId));
            }
        });
    }

    // ---- Settings ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StatementPasswordRepository.SavedPasswordRow> list(UUID userId) {
        return repository.listForUser(userId);
    }

    @Transactional
    public void remove(UUID userId, UUID statementImportId) {
        StatementPassword row = repository.findByStatementImportId(statementImportId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No saved password for this statement."));
        repository.delete(row);
        auditService.record(userId, "STATEMENT_PASSWORD_REMOVED", "StatementImport", statementImportId);
    }

    /** Removes every saved password, including any held by an upload still in the queue. */
    @Transactional
    public int removeAll(UUID userId) {
        int removed = repository.deleteByUserId(userId);
        auditService.record(userId, "STATEMENT_PASSWORDS_REMOVED_ALL", "User", userId, Map.of("removed", removed));
        return removed;
    }

    // ---- deletes that follow the data ----------------------------------------------------------

    /** The statement was deleted. Joins the caller's transaction. */
    @Transactional
    public void deleteForStatement(UUID statementImportId) {
        repository.deleteByStatementImportId(statementImportId);
    }

    /** The account was deleted: its statements are never opened again. Joins the caller's transaction. */
    @Transactional
    public void deleteForAccount(UUID userId, UUID accountId) {
        repository.deleteByAccount(userId, accountId);
    }

    /** The account purge. Joins the caller's transaction. */
    @Transactional
    public void deleteForUser(UUID userId) {
        repository.deleteByUserId(userId);
    }

    /**
     * Hourly: drops passwords held by uploads that can no longer be confirmed or reprocessed, and
     * any left on a deleted statement (a save that raced the statement's delete).
     */
    @Scheduled(fixedDelayString = "${app.statement-passwords.sweep-interval-ms:3600000}",
               initialDelayString = "${app.statement-passwords.sweep-initial-delay-ms:300000}")
    @Transactional
    public void sweepUnusablePasswords() {
        int jobs = repository.deleteUnusableJobPasswords(Instant.now());
        int statements = repository.deleteForDeletedStatements();
        if (jobs + statements > 0) {
            log.info("Removed {} saved statement password(s) nothing can use any more", jobs + statements);
        }
    }

    private Optional<String> decrypt(StatementPassword row) {
        try {
            return Optional.of(encryptionService.decrypt(row.credential()));
        } catch (EncryptionException | IllegalStateException e) {
            // Unreadable (a retired key, a damaged value): behave as if none was saved, so the user
            // is asked again rather than the import failing. Never the value, only the type.
            log.warn("Saved statement password {} could not be decrypted: {}", row.getId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.CONFLICT, "Saving statement passwords is not available yet.");
        }
    }
}
