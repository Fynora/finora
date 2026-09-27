package com.finora.imports;

import com.finora.config.BuildVersionResolver;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.StatementImportExcludedRow;
import com.finora.repository.StatementImportExcludedRowRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * What a statement refresh will need to know about a statement that only the moment of import can
 * record: which build parsed it, and which of its rows the user left out. See V234.
 */
@Component
public class StatementProvenanceRecorder {

    private final BuildVersionResolver buildVersionResolver;
    private final StatementImportExcludedRowRepository excludedRowRepository;

    public StatementProvenanceRecorder(BuildVersionResolver buildVersionResolver,
                                       StatementImportExcludedRowRepository excludedRowRepository) {
        this.buildVersionResolver = buildVersionResolver;
        this.excludedRowRepository = excludedRowRepository;
    }

    /**
     * The build that parsed the rows being confirmed -- the short commit id, as import sessions
     * record it. A session-backed confirm passes the session's own stamp: the rows were parsed at
     * staging, and a deploy landing between staging and confirm must not credit them to the newer
     * build, or the refresh dry run would skip a statement the old parser read. Without a stamp (a
     * path that parses in the same request that confirms), the running build is the one that parsed.
     */
    public String parserVersion(String stagedByVersion) {
        return stagedByVersion != null ? stagedByVersion : buildVersionResolver.currentCommit();
    }

    /** Records the rows the user left out of this statement, with the statement's own facts for each. */
    public void recordExcludedRows(UUID userId, UUID statementImportId, List<ConfirmedRow> excluded) {
        if (excluded.isEmpty()) return;
        excludedRowRepository.saveAll(excluded.stream()
                .map(row -> new StatementImportExcludedRow(statementImportId, userId, row.rowPosition(), row.date(),
                        row.description(), row.amount(), row.type(), row.likelyDuplicate()))
                .toList());
    }
}
