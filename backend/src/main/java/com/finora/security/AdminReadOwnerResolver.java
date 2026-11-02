package com.finora.security;

import com.finora.entity.User;
import com.finora.security.AdminReadAuditPolicy.Owner;
import com.finora.service.IdentityLookup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Finds whose data an admin read returned, from the id in the request, for the audit row
 * {@link AdminUserDataReadAuditInterceptor} writes.
 *
 * <p>Raw SQL against the tables themselves, not the repositories: a soft-deleted statement or
 * account is still someone's data when an admin reads its trace, and the entities' delete filters
 * would hide it. Each lookup is one indexed primary-key or unique-column read. Null when the id is
 * malformed or names nothing; the caller still records the read, with the id, and no owner.
 */
@Component
public class AdminReadOwnerResolver {

    private final JdbcTemplate jdbc;
    private final IdentityLookup identityLookup;

    public AdminReadOwnerResolver(JdbcTemplate jdbc, IdentityLookup identityLookup) {
        this.jdbc = jdbc;
        this.identityLookup = identityLookup;
    }

    @Nullable
    public UUID ownerOf(Owner owner, @Nullable String value) {
        if (value == null || value.isBlank()) return null;
        return switch (owner) {
            case USER -> uuid(value);
            case USER_EMAIL_PARAM -> identityLookup.byEmail(value, User.SCOPE_USER).map(User::getId).orElse(null);
            case ACCOUNT -> byId("SELECT user_id FROM accounts WHERE id = ?", value);
            case IMPORT_JOB -> byId("SELECT user_id FROM import_jobs WHERE id = ?", value);
            case STATEMENT_IMPORT -> byId("SELECT user_id FROM statement_imports WHERE id = ?", value);
            case TRANSACTION -> byId("SELECT user_id FROM transactions WHERE id = ?", value);
            case NOTIFICATION -> byId("SELECT user_id FROM notifications WHERE id = ?", value);
            case ORIGINAL_VIEW -> byId("SELECT subject_user_id FROM statement_original_views WHERE id = ?", value);
            case LEARNING_EVENT -> byId("SELECT user_id FROM merchant_learning_events WHERE id = ?", value);
            case SUPPORT_TICKET -> byId("SELECT user_id FROM support_tickets WHERE id = ?", value);
            case HELD_STATEMENT -> first(jdbc.queryForList(
                    "SELECT user_id FROM held_statements WHERE held_id = ?", UUID.class, value));
            // Null after the account is deleted: the analysis row outlives it, anonymised.
            case ANALYSIS_REFERENCE -> first(jdbc.queryForList(
                    "SELECT user_id FROM statement_analysis_sessions WHERE reference = ?", UUID.class, value));
        };
    }

    @Nullable
    private UUID byId(String sql, String value) {
        UUID id = uuid(value);
        return id == null ? null : first(jdbc.queryForList(sql, UUID.class, id));
    }

    @Nullable
    private static UUID first(List<UUID> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Nullable
    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
