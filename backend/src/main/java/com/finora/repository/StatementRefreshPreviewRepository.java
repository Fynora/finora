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

    /** Previews whose statement has since been deleted or superseded -- see
     *  StatementRefreshDryRunService.runBatch for the race this closes. */
    @Modifying
    @Query(value = """
            DELETE FROM statement_refresh_previews p USING statement_imports s
             WHERE s.id = p.statement_import_id AND (s.deleted_at IS NOT NULL OR s.superseded_by IS NOT NULL)
            """, nativeQuery = true)
    int deleteOrphaned();
}
