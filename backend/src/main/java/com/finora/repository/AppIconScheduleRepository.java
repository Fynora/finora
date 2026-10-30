package com.finora.repository;

import com.finora.entity.AppIconSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppIconScheduleRepository extends JpaRepository<AppIconSchedule, UUID> {

    /** Every window, newest start first: the admin table. */
    List<AppIconSchedule> findAllByOrderByStartsAtDesc();

    /** Windows that have not ended by {@code now}, earliest first: what the public endpoint picks from. */
    List<AppIconSchedule> findByEndsAtAfterOrderByStartsAtAsc(Instant now);

    /**
     * Windows other than {@code excludeId} that overlap [start, end). Half-open on both sides, like
     * the table's exclusion constraint, so a window may start at the exact instant another ends.
     */
    @Query("""
            SELECT s FROM AppIconSchedule s
            WHERE s.startsAt < :end AND s.endsAt > :start AND s.id <> :excludeId
            ORDER BY s.startsAt ASC
            """)
    List<AppIconSchedule> findOverlapping(@Param("start") Instant start, @Param("end") Instant end,
                                          @Param("excludeId") UUID excludeId);
}
