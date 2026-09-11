package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TimelineEventService {

    private final TimelineEventRepository repository;

    public TimelineEventService(TimelineEventRepository repository) {
        this.repository = repository;
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

    @Transactional(readOnly = true)
    public List<TimelineEventDto> listForUser(UUID userId) {
        return repository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .map(e -> new TimelineEventDto(e.getEventType(), e.getBucket(), e.getImportance(),
                        e.isPermanent(), e.getTitle(), e.getDetail(), e.getOccurredAt()))
                .toList();
    }
}
