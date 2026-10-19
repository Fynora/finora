package com.finora.repository;

import com.finora.entity.LoanEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Loan entries (V279). */
public interface LoanEntryRepository extends JpaRepository<LoanEntry, UUID> {
    List<LoanEntry> findByUserId(UUID userId);

    List<LoanEntry> findByUserIdAndPersonKey(UUID userId, String personKey);

    List<LoanEntry> findByUserIdAndTxnId(UUID userId, UUID txnId);

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LoanEntry x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
