package com.finora.imports.original;

import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who opened a held statement's real pages, and when, for the admins working the hold: the trust
 * page's History and the held-import panel. Read from the view rows, so nothing is stored twice.
 * The reason is not here; it is on the audit entry only (see V271).
 */
@Component
public class OriginalViewHistory {

    /** One opening. {@code openedBy} is the opener's email, or "a deleted account". */
    public record Opening(Instant openedAt, UUID viewerId, String openedBy) {}

    private final StatementOriginalViewRepository views;
    private final UserRepository users;

    public OriginalViewHistory(StatementOriginalViewRepository views, UserRepository users) {
        this.views = views;
        this.users = users;
    }

    /** Oldest first. */
    @Transactional(readOnly = true)
    public List<Opening> forJob(UUID jobId) {
        List<StatementOriginalView> rows = views.findByJobIdOrderByOpenedAtAsc(jobId);
        if (rows.isEmpty()) return List.of();
        Map<UUID, String> emails = users.findAllById(rows.stream().map(StatementOriginalView::getViewerId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail, (a, b) -> a));
        return rows.stream()
                .map(v -> new Opening(v.getOpenedAt(), v.getViewerId(),
                        emails.getOrDefault(v.getViewerId(), "a deleted account")))
                .toList();
    }
}
