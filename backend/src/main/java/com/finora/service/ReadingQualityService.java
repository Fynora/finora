package com.finora.service;

import com.finora.dto.ReadingQualityDto;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.util.UserZone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reading quality by bank: per bank and week, statements read, rows read, holds and reader fixes,
 * ranked by hold rate so the bank whose statements most need reader work comes first. See
 * docs/superpowers/specs/2026-11-15-reading-quality-by-bank-design.md.
 *
 * <h2>Where the bank comes from</h2>
 *
 * <p>Every number takes its bank from one place, {@code statement_analysis_sessions.bank_name}, the
 * engine's own detection ({@code DocumentIdentity}: the bank's official name, never the generic
 * fallback). A hold or fix reaches it through its import job's staging session: the worker holds a
 * job with the session it staged ({@code ImportJob.holdForTrustReview}), and the evidence row for
 * that upload carries the same {@code import_session_id} ({@code StatementAnalysisRecorder.recordParsed}).
 * {@code held_statements.bank_name} is not used: it is {@code DetectedAccountInfo.suggestedName},
 * which can be a generic fallback, so its text can differ from the evidence row's for the same bank
 * and a hold rate built from the two would divide one bank's holds by another name's statements.
 *
 * <p>A job with several evidence rows for its session (a re-read) takes the latest that names a
 * bank. A job with none -- an import hold, whose parse failed before the bank was named, or an
 * upload from before V239 -- is counted in "bank not known".
 *
 * <h2>What a fix is</h2>
 *
 * <p>Sid's choice (2026-11-15): the Claude fix job reached {@code FIXED} (dated {@code updated_at}),
 * or an engineer recorded a fix reference on the held statement (dated by its latest
 * {@code FINDINGS_UPDATED} event). One fix per import job, dated by the earlier of the two.
 *
 * <p>Live counts over the tables as they stand: a customer who deletes their account takes their
 * import jobs and holds with them ({@code ON DELETE CASCADE}), so past weeks can go down. Evidence
 * rows have no foreign key to users and stay.
 */
@Service
public class ReadingQualityService {

    /** The periods the page offers. */
    static final Set<Integer> ALLOWED_WEEKS = Set.of(4, 12);

    /** Fewer statements than this and a hold rate says more about luck than about the reader. */
    public static final int MIN_STATEMENTS = 10;

    private static final String ZONE = UserZone.DEFAULT.getId();

    /** The latest evidence row naming a bank for the job {@code j}'s staging session. */
    private static final String BANK_OF_JOB = """
            LEFT JOIN LATERAL (
                SELECT s.bank_name FROM statement_analysis_sessions s
                WHERE j.import_session_id IS NOT NULL
                  AND s.import_session_id = j.import_session_id
                  AND s.bank_name IS NOT NULL
                ORDER BY s.created_at DESC
                LIMIT 1
            ) b ON TRUE
            """;

    private static final String READS = """
            SELECT s.bank_name, (date_trunc('week', s.created_at AT TIME ZONE ?))::date AS week_start,
                   count(*), coalesce(sum(s.row_count), 0)
            FROM statement_analysis_sessions s
            WHERE s.source = 'CUSTOMER_IMPORT' AND s.outcome = 'PARSED' AND s.bank_name IS NOT NULL
              AND s.created_at >= ? AND s.created_at < ?
            GROUP BY 1, 2
            """;

    /** One hold per job: a job held for import review and later for trust review is one statement. */
    private static final String HOLDS = """
            WITH held AS (
                SELECT h.import_job_id AS job_id, h.created_at AS held_at, TRUE AS trust FROM held_statements h
                UNION ALL
                SELECT j.id, j.created_at, FALSE FROM import_jobs j WHERE j.was_held_for_review
            ), per_job AS (
                SELECT job_id, min(held_at) AS held_at, bool_or(trust) AS trust FROM held GROUP BY job_id
            )
            SELECT b.bank_name, (date_trunc('week', p.held_at AT TIME ZONE ?))::date AS week_start, p.trust, count(*)
            FROM per_job p
            JOIN import_jobs j ON j.id = p.job_id
            """ + BANK_OF_JOB + """
            WHERE p.held_at >= ? AND p.held_at < ?
            GROUP BY 1, 2, 3
            """;

    private static final String FIXES = """
            WITH fix_dates AS (
                SELECT c.job_id, c.updated_at AS fixed_at FROM claude_fix_jobs c WHERE c.state = 'FIXED'
                UNION ALL
                SELECT h.import_job_id, e.last_at
                FROM held_statements h
                JOIN (SELECT held_statement_id, max(created_at) AS last_at FROM held_statement_events
                      WHERE event_type = 'FINDINGS_UPDATED' GROUP BY held_statement_id) e
                  ON e.held_statement_id = h.id
                WHERE btrim(coalesce(h.fix_reference, '')) <> ''
            ), per_job AS (
                SELECT job_id, min(fixed_at) AS fixed_at FROM fix_dates GROUP BY job_id
            )
            SELECT b.bank_name, (date_trunc('week', p.fixed_at AT TIME ZONE ?))::date AS week_start, count(*)
            FROM per_job p
            JOIN import_jobs j ON j.id = p.job_id
            """ + BANK_OF_JOB + """
            WHERE p.fixed_at >= ? AND p.fixed_at < ?
            GROUP BY 1, 2
            """;

    private final JdbcTemplate jdbc;

    public ReadingQualityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts for this week and the {@code weeks - 1} before it. */
    @Transactional(readOnly = true)
    public ReadingQualityDto.Report report(int weeks, Instant now) {
        if (!ALLOWED_WEEKS.contains(weeks)) throw new ApiException(ErrorCode.VALIDATION_ERROR);

        LocalDate thisWeek = now.atZone(UserZone.DEFAULT).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstWeek = thisWeek.minusWeeks(weeks - 1L);
        OffsetDateTime from = firstWeek.atStartOfDay(UserZone.DEFAULT).toOffsetDateTime();
        OffsetDateTime to = thisWeek.plusWeeks(1).atStartOfDay(UserZone.DEFAULT).toOffsetDateTime();
        List<LocalDate> weekStarts = new ArrayList<>();
        for (int i = 0; i < weeks; i++) weekStarts.add(firstWeek.plusWeeks(i));

        // bank -> week -> {statements, rows, holds, fixes}; sorted by name for a stable tie order
        Map<String, Map<LocalDate, long[]>> byBank = new TreeMap<>();
        long[] unknown = new long[3]; // import holds, trust holds, fixes

        jdbc.query(READS, rs -> {
            long[] c = cell(byBank, rs.getString(1), rs.getObject(2, LocalDate.class));
            c[0] += rs.getLong(3);
            c[1] += rs.getLong(4);
        }, ZONE, from, to);

        jdbc.query(HOLDS, rs -> {
            String bank = rs.getString(1);
            long count = rs.getLong(4);
            if (bank == null) {
                unknown[rs.getBoolean(3) ? 1 : 0] += count;
            } else {
                cell(byBank, bank, rs.getObject(2, LocalDate.class))[2] += count;
            }
        }, ZONE, from, to);

        jdbc.query(FIXES, rs -> {
            String bank = rs.getString(1);
            long count = rs.getLong(3);
            if (bank == null) {
                unknown[2] += count;
            } else {
                cell(byBank, bank, rs.getObject(2, LocalDate.class))[3] += count;
            }
        }, ZONE, from, to);

        List<ReadingQualityDto.BankRow> rows = new ArrayList<>();
        byBank.forEach((bank, byWeek) -> rows.add(row(bank, byWeek, weekStarts)));
        rows.sort(MOST_IN_NEED_FIRST);

        return new ReadingQualityDto.Report(weeks, MIN_STATEMENTS, weekStarts, rows,
                new ReadingQualityDto.Unknown(unknown[0], unknown[1], unknown[2]));
    }

    /** Banks with a rate first, highest rate first; then banks with too few statements to judge. */
    static final Comparator<ReadingQualityDto.BankRow> MOST_IN_NEED_FIRST = Comparator
            .comparing((ReadingQualityDto.BankRow r) -> r.holdRate() == null)
            .thenComparing(r -> r.holdRate() == null ? 0.0 : -r.holdRate())
            .thenComparing(r -> -r.holds())
            .thenComparing(r -> -r.statementsRead())
            .thenComparing(ReadingQualityDto.BankRow::bank);

    private static long[] cell(Map<String, Map<LocalDate, long[]>> byBank, String bank, LocalDate week) {
        return byBank.computeIfAbsent(bank, k -> new HashMap<>()).computeIfAbsent(week, k -> new long[4]);
    }

    private static ReadingQualityDto.BankRow row(String bank, Map<LocalDate, long[]> byWeek, List<LocalDate> weekStarts) {
        long statements = 0, rowsRead = 0, holds = 0, fixes = 0;
        List<ReadingQualityDto.Week> weeks = new ArrayList<>();
        for (LocalDate week : weekStarts) {
            long[] c = byWeek.getOrDefault(week, new long[4]);
            weeks.add(new ReadingQualityDto.Week(week, c[0], c[1], c[2], c[3]));
            statements += c[0];
            rowsRead += c[1];
            holds += c[2];
            fixes += c[3];
        }
        Double rate = statements >= MIN_STATEMENTS ? (double) holds / statements : null;
        return new ReadingQualityDto.BankRow(bank, statements, rowsRead, holds, rate, fixes, weeks);
    }
}
