package com.finora.activation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * What the import records already say about a user's way to a first statement, read for the
 * activation questions (Gate 2a spec §4) and the deletion reason (§5). Plain SQL on purpose: the
 * answers must count soft-deleted statements, which the entity's {@code @SQLRestriction} hides.
 */
@Component
public class ActivationFacts {

    private final JdbcTemplate jdbc;

    public ActivationFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether any statement was ever imported for this user, deleted ones included. */
    public boolean hasEverImported(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM statement_imports WHERE user_id = ?)", Boolean.class, userId));
    }

    /**
     * Whether a statement this user uploaded was read and then expired unconfirmed at or after
     * {@code since}. The 48-hour session sweep stamps it, for direct uploads and queued imports alike.
     */
    public boolean hasUnconfirmedExpirySince(UUID userId, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM statement_analysis_sessions "
                        + "WHERE user_id = ? AND expired_unconfirmed_at >= ?)",
                Boolean.class, userId, since.atOffset(ZoneOffset.UTC)));
    }
}
