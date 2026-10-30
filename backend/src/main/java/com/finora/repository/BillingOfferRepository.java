package com.finora.repository;

import com.finora.entity.BillingOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BillingOfferRepository extends JpaRepository<BillingOffer, UUID> {

    /** Offers live at {@code now}, cheapest first, so overlapping offers on the same plan and cycle
     *  resolve the same way everywhere they are read. */
    @Query("SELECT o FROM BillingOffer o WHERE o.startsAt <= :now AND o.endsAt > :now "
            + "ORDER BY o.firstPeriodPrice ASC, o.createdAt ASC")
    List<BillingOffer> findLiveAt(@Param("now") Instant now);
}
