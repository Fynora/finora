package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimelineEventServiceTest {

    private TimelineEventRepository repository;
    private TimelineEventService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(TimelineEventRepository.class);
        service = new TimelineEventService(repository);
    }

    @Test
    void record_looksUpBucketImportanceAndPermanent_fromTheEventTypeCatalog_andInsertsIdempotently() {
        Instant occurredAt = Instant.parse("2026-03-01T00:00:00Z");

        service.record(userId, TimelineEventType.GOAL_COMPLETED, UUID.randomUUID(),
                "Completed Emergency Fund", null, occurredAt);

        verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.GOAL_COMPLETED),
                eq("TRANSFORMATION"), eq("LANDMARK"), eq(true), any(), eq("Completed Emergency Fund"),
                isNull(), eq(occurredAt));
    }

    @Test
    void listForUser_returnsEventsNewestFirst_asDtos() {
        TimelineEvent e = new TimelineEvent();
        e.setEventType(TimelineEventType.GOAL_COMPLETED);
        e.setBucket("TRANSFORMATION");
        e.setImportance("LANDMARK");
        e.setPermanent(true);
        e.setTitle("Completed Emergency Fund");
        e.setOccurredAt(Instant.parse("2026-03-01T00:00:00Z"));
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(e));

        List<TimelineEventDto> result = service.listForUser(userId);

        assertThat(result).containsExactly(new TimelineEventDto(
                TimelineEventType.GOAL_COMPLETED, "TRANSFORMATION", "LANDMARK", true,
                "Completed Emergency Fund", null, Instant.parse("2026-03-01T00:00:00Z")));
    }
}
