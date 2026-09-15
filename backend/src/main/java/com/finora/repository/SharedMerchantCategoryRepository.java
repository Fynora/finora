package com.finora.repository;

import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SharedMerchantCategoryRepository extends JpaRepository<SharedMerchantCategory, UUID> {
    Optional<SharedMerchantCategory> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);
    long countByStatus(SharedMerchantCategory.Status status);

    /** Spec §7's time-only exit from Revalidating ("or just time, with nothing further
     *  disagreeing") -- rows whose cooldown window elapsed with no further observation to trigger
     *  the reactive check in SharedCorpusService.recordObservation. Bounded via {@code pageable}. */
    List<SharedMerchantCategory> findByStatusAndRevalidatingSinceBefore(
            SharedMerchantCategory.Status status, Instant cutoff, Pageable pageable);
}
