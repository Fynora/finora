package com.finora.imports.original;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatementOriginalViewRepository extends JpaRepository<StatementOriginalView, UUID> {
    List<StatementOriginalView> findByJobIdOrderByOpenedAtAsc(UUID jobId);
}
