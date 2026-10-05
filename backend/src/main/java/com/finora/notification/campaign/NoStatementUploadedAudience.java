package com.finora.notification.campaign;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * People who have not uploaded a statement: no live statement import AND no import job in any
 * state. A job in any state counts -- someone whose upload is queued, processing, held for review
 * or failed is already past "upload your statement", and for a held one the next move is ours, so
 * asking them to upload would be wrong. A statement the user later deleted ({@code deleted_at}
 * set) no longer counts.
 */
@Component
public class NoStatementUploadedAudience implements AudienceResolver {

    private static final String NO_STATEMENT = """
             AND NOT EXISTS (SELECT 1 FROM statement_imports s
                              WHERE s.user_id = u.id AND s.deleted_at IS NULL)
             AND NOT EXISTS (SELECT 1 FROM import_jobs j WHERE j.user_id = u.id)
            """;

    private final JdbcTemplate jdbc;

    public NoStatementUploadedAudience(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AudienceType type() {
        return AudienceType.NO_STATEMENT_UPLOADED;
    }

    @Override
    public long count() {
        return AudienceSql.count(jdbc, NO_STATEMENT);
    }

    @Override
    public List<UUID> page(UUID afterUserId, int limit) {
        return AudienceSql.page(jdbc, NO_STATEMENT, afterUserId, limit);
    }
}
