package com.finora.repository;

import com.finora.entity.BillingOffer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingOfferRepository extends JpaRepository<BillingOffer, UUID> {

    /** Offers live at {@code now}, cheapest first, so overlapping offers on the same plan and cycle
     *  resolve the same way everywhere they are read. */
    @Query("SELECT o FROM BillingOffer o WHERE o.startsAt <= :now AND o.endsAt > :now "
            + "ORDER BY o.firstPeriodPrice ASC, o.createdAt ASC")
    List<BillingOffer> findLiveAt(@Param("now") Instant now);

    boolean existsByCodeAndPlanIdAndBillingCycle(String code, UUID planId, String billingCycle);

    /** Every offer, past, live and upcoming, newest start first, for the admin page. */
    List<BillingOffer> findAllByOrderByStartsAtDescCreatedAtDesc();

    /** Offers on the same plan and cycle whose window shares any instant with [start, end). Windows
     *  are end-exclusive, so one offer may start at the exact instant another ends. */
    @Query("SELECT o FROM BillingOffer o WHERE o.planId = :planId AND o.billingCycle = :billingCycle "
            + "AND o.startsAt < :end AND o.endsAt > :start ORDER BY o.startsAt ASC")
    List<BillingOffer> findOverlapping(@Param("planId") UUID planId, @Param("billingCycle") String billingCycle,
                                       @Param("start") Instant start, @Param("end") Instant end);

    /** Locks one offer row, so two "end now" requests for it can't both act on a stale read. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM BillingOffer o WHERE o.id = :id")
    Optional<BillingOffer> lockById(@Param("id") UUID id);
}
