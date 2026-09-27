package com.finora.imports.refresh;

import com.finora.config.BuildVersionResolver;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What a build would change if users refreshed their statements -- the dry run's results
 * (StatementRefreshDryRunService), totalled for the admin view. Counts only: no statement text,
 * amounts or user identities.
 */
@Service
public class StatementRefreshSummaryService {

    public record StatusTotals(String status, long statements, long users, long rowsChanged, long rowsAdded,
                               long rowsRemoved, long rowsConflicting, long factsChanged) {}

    public record Summary(String parserVersion, long statementsAwaitingCheck, List<StatusTotals> byStatus) {}

    private final StatementRefreshPreviewRepository previewRepository;
    private final StatementImportRepository statementImportRepository;
    private final BuildVersionResolver buildVersionResolver;

    public StatementRefreshSummaryService(StatementRefreshPreviewRepository previewRepository,
                                          StatementImportRepository statementImportRepository,
                                          BuildVersionResolver buildVersionResolver) {
        this.previewRepository = previewRepository;
        this.statementImportRepository = statementImportRepository;
        this.buildVersionResolver = buildVersionResolver;
    }

    /** @param parserVersion a build's short commit, or null/blank for the running build */
    @Transactional(readOnly = true)
    public Summary summary(String parserVersion) {
        String version = parserVersion != null && !parserVersion.isBlank() ? parserVersion : buildVersionResolver.currentCommit();
        if (version == null) return new Summary(null, 0, List.of());
        List<StatusTotals> totals = previewRepository.totalsFor(version).stream()
                .map(t -> new StatusTotals(t.getStatus(), t.getStatements(), t.getUsers(), t.getRowsChanged(),
                        t.getRowsAdded(), t.getRowsRemoved(), t.getRowsConflicting(), t.getFactsChanged()))
                .toList();
        return new Summary(version, statementImportRepository.countAwaitingRefreshCheck(version), totals);
    }
}
