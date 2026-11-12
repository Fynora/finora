package com.finora.imports.blanked;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LayoutUploadBlankedCopyRepository extends JpaRepository<LayoutUploadBlankedCopy, UUID> {
    List<LayoutUploadBlankedCopy> findByJobIdOrderByMadeAtAsc(UUID jobId);
}
