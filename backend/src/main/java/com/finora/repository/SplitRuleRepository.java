package com.finora.repository;

import com.finora.entity.SplitRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Split rules, removed ones included (V279). */
public interface SplitRuleRepository extends JpaRepository<SplitRule, UUID> {
    List<SplitRule> findByUserId(UUID userId);

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SplitRule x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
