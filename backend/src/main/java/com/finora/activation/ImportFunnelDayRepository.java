package com.finora.activation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ImportFunnelDayRepository extends JpaRepository<ImportFunnelDay, UUID> {

    List<ImportFunnelDay> findByUserIdOrderByFunnelDateAscEventAsc(UUID userId);

    boolean existsByUserIdAndEvent(UUID userId, FunnelEvent event);

    boolean existsByUserIdAndEventAndFunnelDateBefore(UUID userId, FunnelEvent event, LocalDate date);

    /** At most one row per user, event and day: a repeat is a no-op, never an error. */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO import_funnel_days (user_id, event, funnel_date) "
            + "VALUES (:userId, :event, :funnelDate) "
            + "ON CONFLICT (user_id, event, funnel_date) DO NOTHING",
            nativeQuery = true)
    void record(@Param("userId") UUID userId, @Param("event") String event, @Param("funnelDate") LocalDate funnelDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ImportFunnelDay d WHERE d.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
