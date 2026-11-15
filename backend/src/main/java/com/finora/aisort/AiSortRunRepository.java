package com.finora.aisort;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AiSortRunRepository extends JpaRepository<AiSortRun, UUID> {

    Optional<AiSortRun> findByUserIdAndImportsKey(UUID userId, String importsKey);

    Optional<AiSortRun> findByIdAndUserId(UUID id, UUID userId);

    /** Account purge (AccountPurgeSweepService). */
    @Modifying
    @Query("DELETE FROM AiSortRun r WHERE r.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
