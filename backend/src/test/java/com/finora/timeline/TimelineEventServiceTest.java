package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimelineEventServiceTest {

    private TimelineEventRepository repository;
    private GoalRepository goalRepository;
    private BudgetRepository budgetRepository;
    private StatementImportRepository statementImportRepository;
    private TimelineEventService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(TimelineEventRepository.class);
        goalRepository = mock(GoalRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        service = new TimelineEventService(repository, goalRepository, budgetRepository, statementImportRepository);
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
        when(repository.existsByUserId(userId)).thenReturn(true);
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(e));

        List<TimelineEventDto> result = service.listForUser(userId);

        assertThat(result).containsExactly(new TimelineEventDto(
                TimelineEventType.GOAL_COMPLETED, "TRANSFORMATION", "LANDMARK", true,
                "Completed Emergency Fund", null, Instant.parse("2026-03-01T00:00:00Z")));
    }

    @Test
    void listForUser_backfillsStartingMilestones_onFirstCallForAUserWithNoTimelineRowsYet() {
        when(repository.existsByUserId(userId)).thenReturn(false);
        when(goalRepository.findEarliestCreatedAtEverEpochMillis(userId))
                .thenReturn(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        when(budgetRepository.findEarliestCreatedAtEverEpochMillis(userId)).thenReturn(null);
        when(statementImportRepository.findEarliestImportedAtEverEpochMillis(userId)).thenReturn(null);
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.FIRST_GOAL_CREATED),
                eq("STARTING"), eq("LANDMARK"), eq(true), isNull(), any(), isNull(),
                eq(Instant.parse("2026-01-01T00:00:00Z")));
        verify(repository, never()).insertIfNew(eq(userId), eq(TimelineEventType.FIRST_BUDGET_CREATED),
                any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void listForUser_skipsBackfill_whenTheUserAlreadyHasTimelineRows() {
        when(repository.existsByUserId(userId)).thenReturn(true);
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(repository, never()).insertIfNew(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
    }
}
