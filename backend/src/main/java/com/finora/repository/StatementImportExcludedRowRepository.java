package com.finora.repository;

import com.finora.entity.StatementImportExcludedRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatementImportExcludedRowRepository extends JpaRepository<StatementImportExcludedRow, UUID> {

    /** Deletion lives on StatementImportRepository (deleteExcludedRows*), next to the statement
     *  lifecycle it belongs to. */
    List<StatementImportExcludedRow> findByStatementImportIdOrderByRowPositionAsc(UUID statementImportId);
}
