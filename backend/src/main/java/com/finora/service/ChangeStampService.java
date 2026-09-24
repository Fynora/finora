package com.finora.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A short opaque string that changes whenever something the mobile app displays changes for one
 * user: the profile, accounts, transactions, statement imports, budgets or goals.
 *
 * <p>Exists so an app that is open on a phone can learn "something changed on another device" with
 * one cheap request, instead of re-running every screen's query on a timer. The client compares the
 * value with the last one it saw and refetches only when they differ; it never interprets it.
 *
 * <h2>What moves it</h2>
 * Per table: the number of live rows, the sum of their {@code version}, and the newest
 * {@code created_at}. {@code version} is JPA's optimistic-lock counter and moves on every update
 * (an edit, a soft delete -- {@code @SQLDelete} bumps it too), which {@code updated_at} does not:
 * that column is assigned by hand at some 40 call sites, so a change that forgot to set it would be
 * invisible. The row count catches inserts and removals; {@code max(created_at)} catches the one
 * case the other two cannot -- an insert and a removal in the same interval, which leave count and
 * version-sum unchanged. Soft-deleted rows are excluded, so a deletion changes the count.
 *
 * <p>The profile contributes its displayed fields directly (name, email, phone and their verified
 * flags) plus {@code updated_at}: {@code users} has no version column, and {@code updated_at} alone
 * is set by hand, so a rename that forgot it would go unnoticed.
 *
 * <h2>Cost</h2>
 * One round trip, six aggregates over the user's own rows. It is deliberately not built from the
 * dashboard queries -- those are what this exists to avoid re-running.
 *
 * <h2>Bulk writes</h2>
 * A bulk {@code @Modifying} UPDATE bypasses JPA and moves neither {@code version} nor the row
 * count, so it must bump {@code version} itself -- ChangeStampBulkWriteGuardTest fails the build for
 * one on these tables that does not. The only exemption is the counterparty-typing backfill, a
 * system reclassification rather than something a user did, which must not look like an edit.
 */
@Service
public class ChangeStampService {

    private static final String SQL = """
            SELECT
              (SELECT count(*) || ':' || coalesce(sum(version), 0) || ':' || coalesce(max(created_at)::text, '')
                 FROM transactions WHERE user_id = ? AND deleted_at IS NULL),
              (SELECT count(*) || ':' || coalesce(sum(version), 0) || ':' || coalesce(max(created_at)::text, '')
                 FROM accounts WHERE user_id = ? AND deleted_at IS NULL),
              (SELECT count(*) || ':' || coalesce(sum(version), 0) || ':' || coalesce(max(created_at)::text, '')
                 FROM statement_imports WHERE user_id = ? AND deleted_at IS NULL),
              (SELECT count(*) || ':' || coalesce(sum(version), 0) || ':' || coalesce(max(created_at)::text, '')
                 FROM budgets WHERE user_id = ? AND deleted_at IS NULL),
              (SELECT count(*) || ':' || coalesce(sum(version), 0) || ':' || coalesce(max(created_at)::text, '')
                 FROM goals WHERE user_id = ? AND deleted_at IS NULL),
              (SELECT coalesce(full_name, '') || ':' || coalesce(email, '') || ':' || coalesce(phone_number, '')
                      || ':' || phone_verified || ':' || email_verified || ':' || coalesce(updated_at::text, '')
                 FROM users WHERE id = ?)
            """;

    private final JdbcTemplate jdbc;

    public ChangeStampService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public String stampFor(UUID userId) {
        String joined = jdbc.query(SQL, rs -> {
            rs.next();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 6; i++) sb.append(rs.getString(i)).append('|');
            return sb.toString();
        }, userId, userId, userId, userId, userId, userId);
        return sha256Prefix(joined);
    }

    /** Hashed so the value is opaque: a client compares it, it does not read counts out of it. */
    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
