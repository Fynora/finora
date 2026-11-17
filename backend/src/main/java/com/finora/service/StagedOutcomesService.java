package com.finora.service;

import com.finora.dto.StagedOutcomesDto;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.util.UserZone;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What became of the statements the reader staged for customers, week by week: imported, discarded
 * by the customer, removed by the phone app, expired, still open, or nothing recorded -- and how
 * often the Free plan's one-month limit was shown, refused a confirm, or was followed by an import
 * anyway.
 *
 * <p>From the upload records ({@code statement_analysis_sessions}): a customer upload the reader
 * parsed and staged ({@code CUSTOMER_IMPORT}, {@code PARSED}, an {@code import_session_id}), grouped
 * by the week it was staged (Monday, India time), so each week's outcomes add up to its staged count.
 * Its outcome is the first that applies: {@code confirmed_at} (V327), {@code discarded_by} (V326),
 * {@code expired_unconfirmed_at} (V267), a staged session still in place, else not recorded.
 *
 * <p>Customers only, by the same rule as {@link ReadingQualityService}: internal and admin-scope
 * accounts are left out; a deleted customer's records stay.
 */
@Service
public class StagedOutcomesService {

    private static final String ZONE = UserZone.DEFAULT.getId();

    private static final String WEEKLY = """
            SELECT (date_trunc('week', s.created_at AT TIME ZONE ?))::date AS week_start,
                   count(*) AS staged,
                   count(*) FILTER (WHERE s.confirmed_at IS NOT NULL) AS imported,
                   count(*) FILTER (WHERE s.confirmed_at IS NULL AND s.discarded_by = 'CUSTOMER') AS discarded,
                   count(*) FILTER (WHERE s.confirmed_at IS NULL AND s.discarded_by = 'PHONE_MULTI_ACCOUNT') AS phone,
                   count(*) FILTER (WHERE s.confirmed_at IS NULL AND s.discarded_unconfirmed_at IS NULL
                                      AND s.expired_unconfirmed_at IS NOT NULL) AS expired,
                   count(*) FILTER (WHERE s.confirmed_at IS NULL AND s.discarded_unconfirmed_at IS NULL
                                      AND s.expired_unconfirmed_at IS NULL
                                      AND EXISTS (SELECT 1 FROM import_sessions i
                                                  WHERE i.id = s.import_session_id AND i.status = 'STAGED')) AS open,
                   count(*) FILTER (WHERE s.free_limit_shown_at IS NOT NULL) AS shown,
                   count(*) FILTER (WHERE s.free_limit_refused_at IS NOT NULL) AS refused,
                   count(*) FILTER (WHERE s.free_limit_shown_at IS NOT NULL AND s.confirmed_at IS NOT NULL) AS shown_imported,
                   count(*) FILTER (WHERE %1$s AND s.discard_reason = 'WRONG_FILE') AS wrong_file,
                   count(*) FILTER (WHERE %1$s AND s.discard_reason = 'TOO_MANY_MONTHS') AS too_many_months,
                   count(*) FILTER (WHERE %1$s AND s.discard_reason = 'LOOKS_WRONG') AS looks_wrong,
                   count(*) FILTER (WHERE %1$s AND s.discard_reason = 'LATER') AS later,
                   count(*) FILTER (WHERE %1$s AND s.discard_reason = 'OTHER') AS other
            FROM statement_analysis_sessions s
            WHERE s.source = 'CUSTOMER_IMPORT' AND s.outcome = 'PARSED' AND s.import_session_id IS NOT NULL
              AND s.created_at >= ? AND s.created_at < ?
              AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = s.user_id
                               AND (u.internal_account OR u.account_scope <> 'USER'))
            GROUP BY 1
            """.formatted("s.confirmed_at IS NULL AND s.discarded_by = 'CUSTOMER'");

    /** When V327 ran: from then on imports are recorded too (discards and the Free plan limit since
     *  V326, which ran first), so every outcome can be told apart. */
    private static final String RECORDED_SINCE = """
            SELECT (installed_on AT TIME ZONE current_setting('TimeZone') AT TIME ZONE ?)::date
            FROM flyway_schema_history WHERE version = '327' AND success
            """;

    private final JdbcTemplate jdbc;

    public StagedOutcomesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts for this week and the {@code weeks - 1} before it; 4 or 12, as on Reading quality. */
    @Transactional(readOnly = true)
    public StagedOutcomesDto.Report report(int weeks, Instant now) {
        if (!Set.of(4, 12).contains(weeks)) throw new ApiException(ErrorCode.VALIDATION_ERROR);

        LocalDate thisWeek = now.atZone(UserZone.DEFAULT).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstWeek = thisWeek.minusWeeks(weeks - 1L);
        OffsetDateTime from = firstWeek.atStartOfDay(UserZone.DEFAULT).toOffsetDateTime();
        OffsetDateTime to = thisWeek.plusWeeks(1).atStartOfDay(UserZone.DEFAULT).toOffsetDateTime();

        Map<LocalDate, long[]> byWeek = new HashMap<>();
        jdbc.query(WEEKLY, rs -> {
            long[] c = new long[SLOTS];
            for (int i = 0; i < c.length; i++) c[i] = rs.getLong(i + 2);
            byWeek.put(rs.getObject(1, LocalDate.class), c);
        }, ZONE, from, to);

        List<StagedOutcomesDto.Week> weekly = new ArrayList<>();
        long[] total = new long[SLOTS];
        for (int i = 0; i < weeks; i++) {
            LocalDate week = firstWeek.plusWeeks(i);
            long[] c = byWeek.getOrDefault(week, new long[SLOTS]);
            weekly.add(week(week, c));
            for (int k = 0; k < total.length; k++) total[k] += c[k];
        }
        return new StagedOutcomesDto.Report(weeks, recordedSince(), weekly, week(null, total));
    }

    /** Columns after week_start: staged, imported, discarded, phone, expired, open, shown, refused,
     *  shown-then-imported, then the five discard reasons. */
    private static final int SLOTS = 14;

    private static StagedOutcomesDto.Week week(LocalDate weekStart, long[] c) {
        long notRecorded = c[0] - c[1] - c[2] - c[3] - c[4] - c[5];
        long noReason = c[2] - c[9] - c[10] - c[11] - c[12] - c[13];
        return new StagedOutcomesDto.Week(weekStart, c[0], c[1], c[2], c[3], c[4], c[5], notRecorded,
                c[6], c[7], c[8],
                new StagedOutcomesDto.DiscardReasons(c[9], c[10], c[11], c[12], c[13], noReason));
    }

    private LocalDate recordedSince() {
        try {
            // installed_on is a timestamp without zone, in the database session's own time zone:
            // read as that zone, then as an India date, in SQL, so no zone-less type reaches Java.
            List<LocalDate> at = jdbc.queryForList(RECORDED_SINCE, LocalDate.class, ZONE);
            return at.isEmpty() ? null : at.get(0);
        } catch (DataAccessException e) {
            return null; // no migration history to read: say "not known" rather than guess a date
        }
    }
}
