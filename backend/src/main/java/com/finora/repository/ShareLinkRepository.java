package com.finora.repository;

import com.finora.entity.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Shares the user confirmed (V281). */
public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {
    List<ShareLink> findByUserId(UUID userId);

    List<ShareLink> findByUserIdAndBillTxnId(UUID userId, UUID billTxnId);

    List<ShareLink> findByUserIdAndCreditTxnId(UUID userId, UUID creditTxnId);

    /** Link, or change the amount of an existing link -- one statement, so two taps at once
     *  cannot both insert. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
           INSERT INTO share_links (id, user_id, credit_txn_id, bill_txn_id, amount, created_at)
           VALUES (gen_random_uuid(), :userId, :creditId, :billId, :amount, now())
           ON CONFLICT (credit_txn_id, bill_txn_id) DO UPDATE SET amount = EXCLUDED.amount
           """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("creditId") UUID creditId, @Param("billId") UUID billId,
               @Param("amount") java.math.BigDecimal amount);

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ShareLink x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
