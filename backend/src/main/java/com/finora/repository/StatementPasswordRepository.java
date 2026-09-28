package com.finora.repository;

import com.finora.entity.StatementPassword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementPasswordRepository extends JpaRepository<StatementPassword, UUID> {

    Optional<StatementPassword> findByImportJobId(UUID importJobId);

    List<StatementPassword> findByImportJobIdIn(Collection<UUID> importJobIds);

    /** Passwords held by the upload(s) that staged this session. Driven from this small table, so
     *  it needs no index on import_jobs.import_session_id. */
    @Query(value = """
           SELECT p.* FROM statement_passwords p
             JOIN import_jobs j ON j.id = p.import_job_id
            WHERE j.import_session_id = :importSessionId
           """, nativeQuery = true)
    List<StatementPassword> findHeldByJobsOfSession(@Param("importSessionId") UUID importSessionId);

    Optional<StatementPassword> findByStatementImportId(UUID statementImportId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StatementPassword p WHERE p.statementImportId = :statementImportId")
    int deleteByStatementImportId(@Param("statementImportId") UUID statementImportId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StatementPassword p WHERE p.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    /** Every statement of one account -- the account was deleted, and its statements with it. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
           DELETE FROM statement_passwords p
            USING statement_imports s
            WHERE p.statement_import_id = s.id
              AND s.user_id = :userId
              AND s.account_id = :accountId
           """, nativeQuery = true)
    int deleteByAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId);

    /**
     * Job-owned passwords no upload can use any more. Kept only while the job can still run (queued
     * or running, or held -- an admin may reprocess it) or while its staged session can still be
     * confirmed; everything else -- failed, cancelled, a session confirmed elsewhere, expired or
     * gone -- is deleted.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
           DELETE FROM statement_passwords p
            USING import_jobs j
            WHERE p.import_job_id = j.id
              AND j.status NOT IN ('QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING',
                                   'HELD_FOR_REVIEW', 'HELD_FOR_TRUST_REVIEW')
              AND NOT (j.status = 'COMPLETED' AND EXISTS (
                    SELECT 1 FROM import_sessions s
                     WHERE s.id = j.import_session_id
                       AND s.status = 'STAGED'
                       AND s.expires_at > :now))
           """, nativeQuery = true)
    int deleteUnusableJobPasswords(@Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(value = """
           DELETE FROM statement_passwords p
            USING statement_imports s
            WHERE p.statement_import_id = s.id
              AND s.deleted_at IS NOT NULL
           """, nativeQuery = true)
    int deleteForDeletedStatements();

    /** One saved password as Settings lists it: the statement it opens, never the password. */
    interface SavedPasswordRow {
        UUID getStatementImportId();
        String getFileName();
        String getAccountName();
        LocalDate getPeriodStart();
        LocalDate getPeriodEnd();
        Instant getConsentedAt();
    }

    @Query(value = """
           SELECT p.statement_import_id AS statementImportId,
                  s.file_name AS fileName,
                  a.name AS accountName,
                  s.statement_period_start AS periodStart,
                  s.statement_period_end AS periodEnd,
                  p.consented_at AS consentedAt
             FROM statement_passwords p
             JOIN statement_imports s ON s.id = p.statement_import_id AND s.deleted_at IS NULL
             LEFT JOIN accounts a ON a.id = s.account_id AND a.deleted_at IS NULL
            WHERE p.user_id = :userId
            ORDER BY s.statement_period_end DESC NULLS LAST, s.imported_at DESC
           """, nativeQuery = true)
    List<SavedPasswordRow> listForUser(@Param("userId") UUID userId);
}
