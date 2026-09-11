package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TimelineEventService {

    private final TimelineEventRepository repository;
    private final GoalRepository goalRepository;
    private final BudgetRepository budgetRepository;
    private final StatementImportRepository statementImportRepository;

    public TimelineEventService(TimelineEventRepository repository, GoalRepository goalRepository,
                                 BudgetRepository budgetRepository,
                                 StatementImportRepository statementImportRepository) {
        this.repository = repository;
        this.goalRepository = goalRepository;
        this.budgetRepository = budgetRepository;
        this.statementImportRepository = statementImportRepository;
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

    /** Materializes Starting-bucket history the first time a given user's timeline is read, from
     *  the same "earliest ever" native queries FinancialJourneyService used (see the task that
     *  removes that now-superseded service) -- so an existing user's history isn't empty just
     *  because the timeline table didn't exist yet when they signed up. Guarded by
     *  existsByUserId so this only ever runs once per user; record()'s own idempotency is a
     *  second, redundant safety net if two requests race this on the same user. */
    @Transactional
    public List<TimelineEventDto> listForUser(UUID userId) {
        if (!repository.existsByUserId(userId)) {
            backfillStartingMilestones(userId);
        }
        return repository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .map(e -> new TimelineEventDto(e.getEventType(), e.getBucket(), e.getImportance(),
                        e.isPermanent(), e.getTitle(), e.getDetail(), e.getOccurredAt()))
                .toList();
    }

    private void backfillStartingMilestones(UUID userId) {
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
    }
}
