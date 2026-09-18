package com.finora.repository;

import com.finora.entity.AiAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface AiAuditLogRepository extends JpaRepository<AiAuditLog, UUID> {

    // Cost governance (plan §4.4) -- per-user daily cap. COALESCE because SUM over zero rows is
    // SQL NULL, not zero, and a brand-new user with no calls yet must read as "spent nothing," not
    // fail a null-unboxing check at the caller.
    @Query("SELECT COALESCE(SUM(a.cost), 0) FROM AiAuditLog a WHERE a.userId = :userId AND a.createdAt >= :since")
    BigDecimal sumCostByUserSince(@Param("userId") UUID userId, @Param("since") Instant since);

    // Cost governance (plan §4.4) -- org-wide monthly AI budget, same COALESCE reasoning as above.
    @Query("SELECT COALESCE(SUM(a.cost), 0) FROM AiAuditLog a WHERE a.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") Instant since);

    /** AccountPurgeSweepService -- {@code user_id} has no FK at all, so nothing else ever removes
     *  this table's rows for a purged user. Tool inputs/outputs here are constrained to Tier 0/1
     *  aggregate facts by design (see V201's own migration comment), so a hard delete needs no
     *  separate redaction step, unlike {@code audit_logs.redacted_at}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AiAuditLog a WHERE a.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
