package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import com.finora.goals.Goal;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    private NetWorthSnapshotRepository netWorthSnapshotRepository;
    private TimelineEventService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(TimelineEventRepository.class);
        goalRepository = mock(GoalRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        netWorthSnapshotRepository = mock(NetWorthSnapshotRepository.class);
        service = new TimelineEventService(repository, goalRepository, budgetRepository,
                statementImportRepository, netWorthSnapshotRepository);

        // Backfill defaults: "nothing to backfill" for every source, so tests that don't care
        // about a specific backfill path don't NPE on an unstubbed collection/lookup.
        when(goalRepository.findByUserIdIncludingDeleted(any())).thenReturn(List.of());
        when(netWorthSnapshotRepository.findEarliestSnapshotDateAtOrAbove(any(), any())).thenReturn(null);
    }

    private Goal completedGoal(UUID id, String name, Instant completedAt) {
        Goal g = new Goal();
        ReflectionTestUtils.setField(g, "id", id);
        g.setName(name);
        g.setCompletedAt(completedAt);
        return g;
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
                eq("STARTING"), eq("MAJOR"), eq(true), isNull(), any(), isNull(),
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

    // Identity Engine bug-check pass: GOAL_COMPLETED and NET_WORTH_10K/100K must also backfill --
    // their live triggers only fire on a state CROSSING, which an existing user may have already
    // done, silently, before this feature shipped.

    @Test
    void listForUser_backfillsGoalCompleted_forAnAlreadyCompletedGoal() {
        UUID goalId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2025-11-01T00:00:00Z");
        when(repository.existsByUserId(userId)).thenReturn(false);
        when(goalRepository.findByUserIdIncludingDeleted(userId))
                .thenReturn(List.of(completedGoal(goalId, "Emergency Fund", completedAt)));
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.GOAL_COMPLETED),
                eq("TRANSFORMATION"), eq("LANDMARK"), eq(true), eq(goalId),
                eq("Completed Emergency Fund"), isNull(), eq(completedAt));
    }

    @Test
    void listForUser_backfillsTheEarliestCompletedGoal_whenMultipleGoalsAreAlreadyComplete() {
        UUID earlierGoalId = UUID.randomUUID();
        UUID laterGoalId = UUID.randomUUID();
        Instant earlier = Instant.parse("2025-06-01T00:00:00Z");
        Instant later = Instant.parse("2025-11-01T00:00:00Z");
        when(repository.existsByUserId(userId)).thenReturn(false);
        when(goalRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(
                completedGoal(laterGoalId, "Later Goal", later),
                completedGoal(earlierGoalId, "Earlier Goal", earlier)));
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.GOAL_COMPLETED),
                any(), any(), anyBoolean(), eq(earlierGoalId), eq("Completed Earlier Goal"), isNull(), eq(earlier));
        verify(repository, never()).insertIfNew(eq(userId), eq(TimelineEventType.GOAL_COMPLETED),
                any(), any(), anyBoolean(), eq(laterGoalId), any(), any(), any());
    }

    @Test
    void listForUser_doesNotBackfillGoalCompleted_whenNoGoalHasEverBeenCompleted() {
        when(repository.existsByUserId(userId)).thenReturn(false);
        when(goalRepository.findByUserIdIncludingDeleted(userId))
                .thenReturn(List.of(completedGoal(UUID.randomUUID(), "In Progress", null)));
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(repository, never()).insertIfNew(eq(userId), eq(TimelineEventType.GOAL_COMPLETED),
                any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void listForUser_backfillsNetWorthMilestones_whenAlreadyAboveBothThresholds() {
        when(repository.existsByUserId(userId)).thenReturn(false);
        when(netWorthSnapshotRepository.findEarliestSnapshotDateAtOrAbove(userId, BigDecimal.valueOf(10_000)))
                .thenReturn(LocalDate.of(2025, 3, 1));
        when(netWorthSnapshotRepository.findEarliestSnapshotDateAtOrAbove(userId, BigDecimal.valueOf(100_000)))
                .thenReturn(LocalDate.of(2025, 9, 1));
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.NET_WORTH_10K),
                any(), any(), anyBoolean(), isNull(), any(), isNull(), any());
        verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.NET_WORTH_100K),
                any(), any(), anyBoolean(), isNull(), any(), isNull(), any());
    }

    @Test
    void listForUser_doesNotBackfillNetWorthMilestones_whenNeverCrossedEitherThreshold() {
        when(repository.existsByUserId(userId)).thenReturn(false);
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

        service.listForUser(userId);

        verify(repository, never()).insertIfNew(eq(userId), eq(TimelineEventType.NET_WORTH_10K),
                any(), any(), anyBoolean(), any(), any(), any(), any());
        verify(repository, never()).insertIfNew(eq(userId), eq(TimelineEventType.NET_WORTH_100K),
                any(), any(), anyBoolean(), any(), any(), any(), any());
    }
}
