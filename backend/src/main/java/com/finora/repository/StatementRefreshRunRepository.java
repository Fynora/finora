package com.finora.repository;

import com.finora.entity.StatementRefreshRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatementRefreshRunRepository extends JpaRepository<StatementRefreshRun, UUID> {

    List<StatementRefreshRun> findByStatementImportIdOrderByCreatedAtDesc(UUID statementImportId);
}
