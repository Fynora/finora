package com.finora.repository;

import com.finora.entity.StatementRefreshPreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRefreshPreviewRepository extends JpaRepository<StatementRefreshPreview, UUID> {

    Optional<StatementRefreshPreview> findByStatementImportIdAndParserVersion(UUID statementImportId, String parserVersion);

    List<StatementRefreshPreview> findByParserVersion(String parserVersion);

    /** A statement keeps only its latest preview. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StatementRefreshPreview p WHERE p.statementImportId = :statementImportId AND p.parserVersion <> :parserVersion")
    int deleteOlderThan(@Param("statementImportId") UUID statementImportId, @Param("parserVersion") String parserVersion);

    /** Totals for one build, by status -- the admin view of what a release changes. */
    interface StatusTotals {
        String getStatus();
        long getStatements();
        long getUsers();
        long getRowsChanged();
        long getRowsAdded();
        long getRowsRemoved();
        long getRowsConflicting();
        long getFactsChanged();
    }

    @Query(value = """
            SELECT status AS status, count(*) AS statements, count(DISTINCT user_id) AS users,
                   coalesce(sum(rows_changed), 0) AS rowsChanged, coalesce(sum(rows_added), 0) AS rowsAdded,
                   coalesce(sum(rows_removed), 0) AS rowsRemoved, coalesce(sum(rows_conflicting), 0) AS rowsConflicting,
                   coalesce(sum(facts_changed), 0) AS factsChanged
              FROM statement_refresh_previews
             WHERE parser_version = :parserVersion
             GROUP BY status
            """, nativeQuery = true)
    List<StatusTotals> totalsFor(@Param("parserVersion") String parserVersion);

    /** One of the user's statements a refresh would change, or that needs its password to be checked. */
    interface PendingStatement {
        UUID getStatementImportId();
        String getStatus();
        String getFileName();
        String getAccountName();
        java.time.LocalDate getPeriodStart();
        java.time.LocalDate getPeriodEnd();
        int getRowsChanged();
        int getRowsAdded();
        int getRowsRemoved();
        int getRowsConflicting();
        int getFactsChanged();
        boolean getPasswordSaved();
        java.time.Instant getComputedAt();
    }

    /**
     * The user's statements whose latest check found something to do: CHANGES, or NEEDS_PASSWORD.
     * Live, not replaced, on a live account -- the same statements a refresh accepts. A refresh
     * deletes a statement's previews, so a statement leaves this list once it has been refreshed.
     */
    @Query(value = """
            SELECT p.statement_import_id AS statementImportId, p.status AS status,
                   s.file_name AS fileName, a.name AS accountName,
                   s.statement_period_start AS periodStart, s.statement_period_end AS periodEnd,
                   p.rows_changed AS rowsChanged, p.rows_added AS rowsAdded, p.rows_removed AS rowsRemoved,
                   p.rows_conflicting AS rowsConflicting, p.facts_changed AS factsChanged,
                   EXISTS (SELECT 1 FROM statement_passwords sp WHERE sp.statement_import_id = s.id) AS passwordSaved,
                   p.computed_at AS computedAt
              FROM statement_refresh_previews p
              JOIN statement_imports s ON s.id = p.statement_import_id
                   AND s.deleted_at IS NULL AND s.superseded_by IS NULL
              JOIN accounts a ON a.id = s.account_id AND a.deleted_at IS NULL
             WHERE p.user_id = :userId AND p.status IN ('CHANGES', 'NEEDS_PASSWORD')
             ORDER BY s.statement_period_end DESC NULLS LAST, s.imported_at DESC
            """, nativeQuery = true)
    List<PendingStatement> pendingForUser(@Param("userId") UUID userId);

    /** Previews whose statement has since been deleted or superseded -- see
     *  StatementRefreshDryRunService.runBatch for the race this closes. */
    @Modifying
    @Query(value = """
            DELETE FROM statement_refresh_previews p USING statement_imports s
             WHERE s.id = p.statement_import_id AND (s.deleted_at IS NOT NULL OR s.superseded_by IS NOT NULL)
            """, nativeQuery = true)
    int deleteOrphaned();
}
