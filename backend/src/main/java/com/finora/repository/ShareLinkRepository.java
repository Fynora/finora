package com.finora.repository;

import com.finora.entity.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Shares the user confirmed (V279). */
public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {
    List<ShareLink> findByUserId(UUID userId);

    List<ShareLink> findByUserIdAndBillTxnId(UUID userId, UUID billTxnId);

    List<ShareLink> findByUserIdAndCreditTxnId(UUID userId, UUID creditTxnId);

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ShareLink x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
