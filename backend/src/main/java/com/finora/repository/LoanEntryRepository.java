package com.finora.repository;

import com.finora.entity.LoanEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Loan entries (V281). */
public interface LoanEntryRepository extends JpaRepository<LoanEntry, UUID> {
    List<LoanEntry> findByUserId(UUID userId);

    List<LoanEntry> findByUserIdAndPersonKey(UUID userId, String personKey);

    List<LoanEntry> findByUserIdAndTxnId(UUID userId, UUID txnId);

    /** Record an answer on a payment, or change its amount -- one statement, so two taps at once
     *  cannot both insert. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
           INSERT INTO loan_entries (id, user_id, person_key, txn_id, direction, amount, created_at)
           VALUES (gen_random_uuid(), :userId, :personKey, :txnId, :direction, :amount, now())
           ON CONFLICT (txn_id, direction) DO UPDATE SET amount = EXCLUDED.amount
           """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("personKey") String personKey, @Param("txnId") UUID txnId,
               @Param("direction") String direction, @Param("amount") java.math.BigDecimal amount);

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LoanEntry x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
