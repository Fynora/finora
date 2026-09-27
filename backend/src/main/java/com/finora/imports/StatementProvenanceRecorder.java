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
 * record: which build parsed it, and which of its rows the user left out. See V233.
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
     * The build confirming the import -- the short commit id, as import sessions record it. The rows
     * were parsed at staging, usually seconds to minutes earlier; a deploy landing in between would
     * attribute them to the newer build, which a refresh dry run then simply finds unchanged.
     */
    public String currentParserVersion() {
        return buildVersionResolver.currentCommit();
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
