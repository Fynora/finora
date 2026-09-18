package com.finora.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserChecklistEventRepository extends JpaRepository<UserChecklistEvent, UUID> {
    List<UserChecklistEvent> findByUserId(UUID userId);
    boolean existsByUserIdAndItemKey(UUID userId, String itemKey);

    /** AccountPurgeSweepService -- {@code user_id} carries a plain FK to {@code users(id)} with no
     *  {@code ON DELETE CASCADE}, and this flow anonymizes the {@code users} row rather than
     *  deleting it, so nothing else ever removes this table's rows for a purged user. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserChecklistEvent e WHERE e.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
