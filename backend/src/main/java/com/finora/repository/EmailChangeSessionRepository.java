package com.finora.repository;

import com.finora.entity.EmailChangeSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmailChangeSessionRepository extends JpaRepository<EmailChangeSession, UUID> {
    /** Scoped to the caller's own userId -- a session ID alone must never be enough to act on
     *  someone else's in-progress email change, even if it were somehow guessed/leaked. */
    Optional<EmailChangeSession> findByIdAndUserId(UUID id, UUID userId);

    /** AccountPurgeSweepService -- {@code user_id} carries a plain FK to {@code users(id)} with no
     *  {@code ON DELETE CASCADE}, and this flow anonymizes the {@code users} row rather than
     *  deleting it, so nothing else ever removes this table's rows for a purged user. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM EmailChangeSession e WHERE e.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
