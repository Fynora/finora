package com.finora.repository;

import com.finora.entity.SenderInflowRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SenderInflowRuleRepository extends JpaRepository<SenderInflowRule, UUID> {

    List<SenderInflowRule> findByUserId(UUID userId);

    Optional<SenderInflowRule> findByUserIdAndCounterpartyKey(UUID userId, String counterpartyKey);

    long countByInflowKindId(UUID inflowKindId);

    @Modifying
    @Query("DELETE FROM SenderInflowRule r WHERE r.userId = :userId")
    int hardDeleteByUserId(@Param("userId") UUID userId);
}
