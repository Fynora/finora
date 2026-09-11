package com.finora.timeline;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, UUID> {

    List<TimelineEvent> findByUserIdOrderByOccurredAtDesc(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Idempotent by construction (ON CONFLICT DO NOTHING against the two partial unique
     *  indexes from V193), not check-then-insert -- a check-then-insert here would hit the
     *  exact "the INSERT is deferred to flush at commit" trap BudgetService.upsert's own doc
     *  comment already documents for this codebase: a try/catch around a plain save() cannot
     *  observe the constraint violation in time to react to it. This also means a call site
     *  never needs to catch anything -- a duplicate call is simply a no-op. */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO timeline_events
            (id, user_id, event_type, bucket, importance, permanent, reference_id, title, detail, occurred_at, created_at)
        VALUES
            (gen_random_uuid(), :userId, :eventType, :bucket, :importance, :permanent, :referenceId, :title, :detail, :occurredAt, now())
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void insertIfNew(@Param("userId") UUID userId, @Param("eventType") String eventType,
                      @Param("bucket") String bucket, @Param("importance") String importance,
                      @Param("permanent") boolean permanent, @Param("referenceId") UUID referenceId,
                      @Param("title") String title, @Param("detail") String detail,
                      @Param("occurredAt") Instant occurredAt);
}
