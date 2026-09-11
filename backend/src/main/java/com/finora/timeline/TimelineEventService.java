package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import com.finora.goals.Goal;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.StatementImportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TimelineEventService {

    private final TimelineEventRepository repository;
    private final GoalRepository goalRepository;
    private final BudgetRepository budgetRepository;
    private final StatementImportRepository statementImportRepository;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;

    public TimelineEventService(TimelineEventRepository repository, GoalRepository goalRepository,
                                 BudgetRepository budgetRepository,
                                 StatementImportRepository statementImportRepository,
                                 NetWorthSnapshotRepository netWorthSnapshotRepository) {
        this.repository = repository;
        this.goalRepository = goalRepository;
        this.budgetRepository = budgetRepository;
        this.statementImportRepository = statementImportRepository;
        this.netWorthSnapshotRepository = netWorthSnapshotRepository;
    }

    /** Called from GoalService/BudgetService/NetWorthService right after their own write.
     *  Idempotent (see TimelineEventRepository.insertIfNew's own doc comment) -- safe to call
     *  on every goal contribution even though most calls will be no-ops for singleton event
     *  types. Runs in the caller's existing transaction deliberately: if the caller's write
     *  rolls back, the timeline entry should roll back with it. */
    @Transactional
    public void record(UUID userId, String eventType, UUID referenceId, String title, String detail, Instant occurredAt) {
        TimelineEventType.Definition def = TimelineEventType.definitionOf(eventType);
        repository.insertIfNew(userId, eventType, def.bucket(), def.importance(), def.permanent(),
                referenceId, title, detail, occurredAt);
    }

    /** Materializes history the first time a given user's timeline is read, from data that
     *  already existed before this feature shipped -- so an existing user's history isn't empty,
     *  or missing Landmark milestones they'd already earned, just because the timeline table
     *  didn't exist yet when they earned them. Guarded by existsByUserId so this only ever runs
     *  once per user; record()'s own idempotency is a second, redundant safety net if two
     *  requests race this on the same user.
     *
     *  <p>Covers both Starting-bucket "first X" events (from the same "earliest ever" native
     *  queries FinancialJourneyService used before it was superseded) and the two Transformation
     *  events whose LIVE trigger only fires on a state CROSSING (goal not-completed to completed;
     *  net worth below to at/above a threshold) -- a user who already crossed either line before
     *  this feature existed would otherwise never get that Landmark event at all, silently,
     *  forever. */
    @Transactional
    public List<TimelineEventDto> listForUser(UUID userId) {
        if (!repository.existsByUserId(userId)) {
            backfillHistoricalMilestones(userId);
        }
        return repository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .map(e -> new TimelineEventDto(e.getEventType(), e.getBucket(), e.getImportance(),
                        e.isPermanent(), e.getTitle(), e.getDetail(), e.getOccurredAt()))
                .toList();
    }

    private void backfillHistoricalMilestones(UUID userId) {
        Long firstGoal = goalRepository.findEarliestCreatedAtEverEpochMillis(userId);
        if (firstGoal != null) {
            record(userId, TimelineEventType.FIRST_GOAL_CREATED, null, "Started your first goal",
                    null, Instant.ofEpochMilli(firstGoal));
        }
        Long firstBudget = budgetRepository.findEarliestCreatedAtEverEpochMillis(userId);
        if (firstBudget != null) {
            record(userId, TimelineEventType.FIRST_BUDGET_CREATED, null, "Created your first budget",
                    null, Instant.ofEpochMilli(firstBudget));
        }
        Long firstImport = statementImportRepository.findEarliestImportedAtEverEpochMillis(userId);
        if (firstImport != null) {
            record(userId, TimelineEventType.FIRST_IMPORT, null, "Imported your first statement",
                    null, Instant.ofEpochMilli(firstImport));
        }

        // Needs the actual Goal (id + name), not just a timestamp -- findByUserIdIncludingDeleted
        // (already used by DataExportService for the same "survives deletion" reason) is the only
        // existing query that gives us that; picking the minimum completedAt in Java over a
        // per-user goal list is cheap at any realistic goal count and avoids a second bespoke
        // native query for what's already fetchable.
        Optional<Goal> earliestCompleted = goalRepository.findByUserIdIncludingDeleted(userId).stream()
                .filter(g -> g.getCompletedAt() != null)
                .min(Comparator.comparing(Goal::getCompletedAt));
        earliestCompleted.ifPresent(g -> record(userId, TimelineEventType.GOAL_COMPLETED, g.getId(),
                "Completed " + g.getName(), null, g.getCompletedAt()));

        backfillNetWorthMilestoneIfAlreadyCrossed(userId, BigDecimal.valueOf(10_000),
                TimelineEventType.NET_WORTH_10K, "Saved your first ₹10,000");
        backfillNetWorthMilestoneIfAlreadyCrossed(userId, BigDecimal.valueOf(100_000),
                TimelineEventType.NET_WORTH_100K, "Reached ₹1,00,000 saved");
    }

    private void backfillNetWorthMilestoneIfAlreadyCrossed(UUID userId, BigDecimal threshold,
                                                             String eventType, String title) {
        LocalDate earliestAtOrAbove = netWorthSnapshotRepository.findEarliestSnapshotDateAtOrAbove(userId, threshold);
        if (earliestAtOrAbove != null) {
            record(userId, eventType, null, title, null, earliestAtOrAbove.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
    }
}
