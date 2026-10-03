package com.finora.repository;

import com.finora.entity.StatementRefreshRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatementRefreshRunRepository extends JpaRepository<StatementRefreshRun, UUID> {

    List<StatementRefreshRun> findByStatementImportIdOrderByCreatedAtDesc(UUID statementImportId);

    java.util.Optional<StatementRefreshRun> findFirstByStatementImportIdOrderByCreatedAtDesc(UUID statementImportId);

    /** DataExportService -- every refresh of every one of this user's statements. */
    List<StatementRefreshRun> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
