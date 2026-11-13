package com.finora.observability.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reads and writes the two background-job tables (V318).
 *
 * <p>Plain SQL rather than JPA entities: a run's record is an upsert whose counters
 * ({@code consecutive_failures}, {@code broken_since}) must be computed from the row as it stands,
 * atomically, when two backend instances run the same job. Each write runs in its own transaction
 * ({@code REQUIRES_NEW}), so it is never part of, or rolled back with, a job's own transaction.
 */
@Component
public class BackgroundJobStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    public BackgroundJobStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void started(String jobKey, String instance, Instant at) {
        ownTransaction.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO background_job_status (job_key, last_started_at, last_instance, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (job_key) DO UPDATE SET
                    last_started_at = EXCLUDED.last_started_at,
                    last_instance = EXCLUDED.last_instance,
                    updated_at = now()
                """, jobKey, utc(at), instance));
    }

    /**
     * Records a finished run. OK and OFF are successes (they set {@code last_success_at} and clear the
     * failure streak); CRASHED and PARTLY_FAILED extend the streak, keep the first {@code broken_since},
     * and add a failure row.
     */
    public void finished(String jobKey, String instance, Instant at, JobOutcome outcome,
                         int failedItems, int handled, String errorSummary) {
        boolean failing = outcome == JobOutcome.CRASHED || outcome == JobOutcome.PARTLY_FAILED;
        OffsetDateTime when = utc(at);
        ownTransaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO background_job_status (job_key, last_finished_at, last_outcome,
                        last_failed_items, last_handled, last_success_at, consecutive_failures,
                        broken_since, last_instance, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (job_key) DO UPDATE SET
                        last_finished_at = EXCLUDED.last_finished_at,
                        last_outcome = EXCLUDED.last_outcome,
                        last_failed_items = EXCLUDED.last_failed_items,
                        last_handled = EXCLUDED.last_handled,
                        last_success_at = COALESCE(EXCLUDED.last_success_at, background_job_status.last_success_at),
                        consecutive_failures = CASE WHEN EXCLUDED.consecutive_failures > 0
                            THEN background_job_status.consecutive_failures + 1 ELSE 0 END,
                        broken_since = CASE WHEN EXCLUDED.consecutive_failures > 0
                            THEN COALESCE(background_job_status.broken_since, EXCLUDED.broken_since) ELSE NULL END,
                        last_instance = EXCLUDED.last_instance,
                        updated_at = now()
                    """,
                    jobKey, when, outcome.name(), failedItems, handled,
                    failing ? null : when, failing ? 1 : 0, failing ? when : null, instance);
            if (failing) {
                jdbc.update("""
                        INSERT INTO background_job_failures (job_key, occurred_at, outcome, failed_items, error_summary)
                        VALUES (?, ?, ?, ?, ?)
                        """, jobKey, when, outcome.name(), failedItems, errorSummary);
            }
        });
    }

    /**
     * Claims the right to send {@code kind} for a job: succeeds (returns 1) only if the job's last
     * email is still {@code expectedKind}, so when two instances decide the same email at once, one
     * sends it and the other gets 0 and stays quiet.
     */
    public int claimEmail(String jobKey, String expectedKind, AlertKind kind, Instant at) {
        OffsetDateTime when = utc(at);
        return jdbc.update("""
                INSERT INTO background_job_status (job_key, last_email_kind, last_email_at, last_broken_email_at, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (job_key) DO UPDATE SET
                    last_email_kind = EXCLUDED.last_email_kind,
                    last_email_at = EXCLUDED.last_email_at,
                    last_broken_email_at = COALESCE(EXCLUDED.last_broken_email_at, background_job_status.last_broken_email_at),
                    updated_at = now()
                WHERE background_job_status.last_email_kind IS NOT DISTINCT FROM ?
                """, jobKey, kind.name(), when, kind == AlertKind.BROKEN ? when : null, expectedKind);
    }

    /** Deletes failure rows older than {@code before}; returns how many. */
    public int pruneFailuresBefore(Instant before) {
        return jdbc.update("DELETE FROM background_job_failures WHERE occurred_at < ?", utc(before));
    }

    public List<BackgroundJobStatus> findAll() {
        return jdbc.query("SELECT * FROM background_job_status", (rs, i) -> statusOf(rs));
    }

    /** A job's failure rows since {@code since}, newest first. */
    public List<BackgroundJobFailure> failuresSince(String jobKey, Instant since) {
        return jdbc.query("""
                SELECT occurred_at, outcome, failed_items, error_summary FROM background_job_failures
                WHERE job_key = ? AND occurred_at >= ? ORDER BY occurred_at DESC
                """,
                (rs, i) -> new BackgroundJobFailure(rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        JobOutcome.valueOf(rs.getString("outcome")), rs.getInt("failed_items"),
                        rs.getString("error_summary")),
                jobKey, utc(since));
    }

    private static BackgroundJobStatus statusOf(ResultSet rs) throws SQLException {
        String outcome = rs.getString("last_outcome");
        return new BackgroundJobStatus(rs.getString("job_key"),
                instant(rs, "last_started_at"), instant(rs, "last_finished_at"),
                outcome == null ? null : JobOutcome.valueOf(outcome),
                rs.getInt("last_failed_items"), rs.getInt("last_handled"), instant(rs, "last_success_at"),
                rs.getInt("consecutive_failures"), instant(rs, "broken_since"), rs.getString("last_email_kind"),
                instant(rs, "last_email_at"), instant(rs, "last_broken_email_at"), rs.getString("last_instance"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
