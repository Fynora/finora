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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Reading quality by bank: per bank and week, statements read, rows read, failed reads, holds and
 * reader fixes, ranked by how often a bank's statements were held or failed, so the bank whose
 * statements most need reader work comes first. See
 * docs/superpowers/specs/2026-11-15-reading-quality-by-bank-design.md.
 *
 * <h2>What is counted</h2>
 *
 * <ul>
 *   <li><b>Statements read</b>: customer uploads the reader parsed ({@code CUSTOMER_IMPORT},
 *       {@code PARSED}), one per upload record.</li>
 *   <li><b>Failed reads</b> (Sid, 2026-11-15): customer uploads the reader failed on for a reason
 *       that is the reader's, not the customer's -- {@link #READER_FAILURE_CODES}, plus any
 *       unexpected exception (its {@code failure_code} is the exception's class name, not an
 *       {@link ErrorCode}). A wrong password, a damaged or empty file, the wrong kind of document, a
 *       statement with no activity or a busy server is left out. One per file: a worker's automatic
 *       retry and a customer uploading the same failing file again write a row each, and are one
 *       failure ({@code user_id} + {@code content_hash}; a row without a hash counts alone).</li>
 *   <li><b>Holds</b>: statements held for trust review, plus imports held for triage
 *       ({@code was_held_for_review}) that are not already a failed read. An import hold is a read
 *       that failed with an unexpected exception, so when its failure row names a bank it is
 *       counted there, once, as a failed read.</li>
 *   <li><b>Rate</b>: (holds + failed reads) / (statements read + failed reads), shown only from
 *       {@link #MIN_STATEMENTS} statements tried. A file that failed and was later read counts as
 *       two tries, one of them failed.</li>
 *   <li><b>Fixes</b> (Sid, 2026-11-15): the Claude fix job reached {@code FIXED} (dated
 *       {@code updated_at}), or an engineer recorded a fix reference on the held statement (dated
 *       by its latest {@code FINDINGS_UPDATED} event). One per import job, dated by the earlier.</li>
 * </ul>
 *
 * <p>Internal accounts (the founder's, testers', {@code users.internal_account}) and admin-scope
 * accounts are left out, as on the activation page (Sid, 2026-11-15). A deleted customer's upload
 * records stay (no foreign key to users) and are counted.
 *
 * <h2>Where the bank comes from</h2>
 *
 * <p>From {@code statement_analysis_sessions.bank_name}, the engine's own detection
 * ({@code DocumentIdentity}: the bank's official name, never the generic fallback), for every
 * number. A hold or fix reaches it through its import job, in this order:
 *
 * <ol>
 *   <li>The job's staging session: the worker holds a job with the session it staged
 *       ({@code ImportJob.holdForTrustReview}), and that upload's record carries the same
 *       {@code import_session_id} ({@code StatementAnalysisRecorder.recordParsed}). The latest
 *       record naming a bank wins.</li>
 *   <li>When that session has no upload record at all: {@code held_statements.bank_name}, and only
 *       if that exact name is one the upload records already use. Re-reading a held statement -- an
 *       admin's "re-run parser", and the Claude fix's own re-read once its PR deploys, both
 *       {@code HeldStatementService.rerunParser} -- stages the re-read under a new session, moves the
 *       job onto it ({@code ImportJob.replaceHeldSession}) and writes no upload record, and nothing
 *       keeps the old session's id. For a recognised bank the hold's name and the record's are both
 *       {@code BankRegistry}'s official name. The generic fallback ("Bank Statement Import") and a
 *       name no record carries go to "bank not known" rather than becoming a row of their own.
 *       Not used when the session's record exists and names no bank: that read is under no bank,
 *       so its hold is not either.</li>
 *   <li>The job's failed read: the latest reader failure of the same customer and file
 *       ({@code content_hash}) naming a bank. This is what names an import hold's bank.</li>
 * </ol>
 *
 * <p>{@code held_statements.bank_name} is not used otherwise: it is
 * {@code DetectedAccountInfo.suggestedName}, possibly filled from a layout profile, so for the same
 * statement it can differ from the record's, and a rate built from both would divide one name's
 * holds by another name's reads. A job with no bank by any route is counted in "bank not known".
 *
 * <p>Live counts over the tables as they stand: a customer who deletes their account takes their
 * import jobs and holds with them ({@code ON DELETE CASCADE}), so past weeks can go down.
 */
@Service
public class ReadingQualityService {

    /** The periods the page offers. */
    static final Set<Integer> ALLOWED_WEEKS = Set.of(4, 12);

    /** Fewer statements tried than this and a rate says more about luck than about the reader. */
    public static final int MIN_STATEMENTS = 10;

    /**
     * The known failures that are the reader's: {@code ExtractionCheck} throws all three when a
     * document yields no transactions, and says so of each -- no table found, a table found but no
     * row read, and no text to read ("Finora cannot read images yet, which is ours and not
     * theirs").
     */
    static final Set<ErrorCode> READER_FAILURE_CODES = Set.of(
            ErrorCode.IMPORT_NO_HEADER_DETECTED,
            ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND,
            ErrorCode.IMPORT_SCANNED_OCR_REQUIRED);

    private static final String ZONE = UserZone.DEFAULT.getId();

    /** SQL list of every {@link ErrorCode} name that is not a reader failure. Enum names only. */
    private static final String NOT_READER_CODES = Arrays.stream(ErrorCode.values())
            .filter(c -> !READER_FAILURE_CODES.contains(c))
            .map(c -> "'" + c.name() + "'")
            .sorted()
            .collect(Collectors.joining(", "));

    /** A failed upload record {@code f} whose failure is the reader's. */
    private static final String READER_FAILURE = """
            f.source = 'CUSTOMER_IMPORT' AND f.outcome = 'FAILED'
              AND (f.failure_code IS NULL OR f.failure_code NOT IN (%s))
            """.formatted(NOT_READER_CODES);

    /** The user {@code %s} is not internal and not an admin account; a deleted user is a customer. */
    private static final String CUSTOMER = """
            NOT EXISTS (SELECT 1 FROM users u WHERE u.id = %s
                         AND (u.internal_account OR u.account_scope <> 'USER'))
            """;

    /** Joins the bank of the job {@code j}; select it as {@link #BANK}. See the class doc. */
    private static final String BANK_OF_JOB = """
            LEFT JOIN LATERAL (
                SELECT count(*) > 0 AS has_evidence,
                       (array_agg(s.bank_name ORDER BY s.created_at DESC)
                            FILTER (WHERE s.bank_name IS NOT NULL))[1] AS bank_name
                FROM statement_analysis_sessions s
                WHERE j.import_session_id IS NOT NULL AND s.import_session_id = j.import_session_id
            ) ev ON TRUE
            LEFT JOIN held_statements hb ON hb.import_job_id = j.id
            LEFT JOIN (SELECT DISTINCT bank_name FROM statement_analysis_sessions WHERE bank_name IS NOT NULL) kb
              ON NOT ev.has_evidence AND kb.bank_name = hb.bank_name
            LEFT JOIN LATERAL (
                SELECT f.bank_name FROM statement_analysis_sessions f
                WHERE j.content_hash IS NOT NULL AND f.user_id = j.user_id AND f.content_hash = j.content_hash
                  AND f.bank_name IS NOT NULL AND %s
                ORDER BY f.created_at DESC
                LIMIT 1
            ) fb ON TRUE
            """.formatted(READER_FAILURE);

    private static final String BANK = "coalesce(ev.bank_name, kb.bank_name, fb.bank_name)";

    private static final String READS = """
            SELECT s.bank_name, (date_trunc('week', s.created_at AT TIME ZONE ?))::date AS week_start,
                   count(*), coalesce(sum(s.row_count), 0)
            FROM statement_analysis_sessions s
            WHERE s.source = 'CUSTOMER_IMPORT' AND s.outcome = 'PARSED' AND s.bank_name IS NOT NULL
              AND s.created_at >= ? AND s.created_at < ?
              AND %s
            GROUP BY 1, 2
            """.formatted(CUSTOMER.formatted("s.user_id"));

    /** One per file, dated by its first failure in the period, under the bank its first row names. */
    private static final String FAILED_READS = """
            WITH files AS (
                SELECT coalesce(f.user_id::text || ':' || f.content_hash, f.id::text) AS file_key,
                       min(f.created_at) AS failed_at,
                       (array_agg(f.bank_name ORDER BY f.created_at))[1] AS bank_name
                FROM statement_analysis_sessions f
                WHERE %s AND f.bank_name IS NOT NULL
                  AND f.created_at >= ? AND f.created_at < ?
                  AND %s
                GROUP BY 1
            )
            SELECT bank_name, (date_trunc('week', failed_at AT TIME ZONE ?))::date AS week_start, count(*)
            FROM files
            GROUP BY 1, 2
            """.formatted(READER_FAILURE, CUSTOMER.formatted("f.user_id"));

    /**
     * One hold per job: a job held for import review and later for trust review is one statement.
     * An import-only hold whose failure names a bank is that bank's failed read, so not a hold too.
     */
    private static final String HOLDS = """
            WITH held AS (
                SELECT h.import_job_id AS job_id, h.created_at AS held_at, TRUE AS trust FROM held_statements h
                UNION ALL
                SELECT j.id, j.created_at, FALSE FROM import_jobs j WHERE j.was_held_for_review
            ), per_job AS (
                SELECT job_id, min(held_at) AS held_at, bool_or(trust) AS trust FROM held GROUP BY job_id
            )
            SELECT %s, (date_trunc('week', p.held_at AT TIME ZONE ?))::date AS week_start, p.trust, count(*)
            FROM per_job p
            JOIN import_jobs j ON j.id = p.job_id
            %s
            WHERE p.held_at >= ? AND p.held_at < ?
              AND (p.trust OR fb.bank_name IS NULL)
              AND %s
            GROUP BY 1, 2, 3
            """.formatted(BANK, BANK_OF_JOB, CUSTOMER.formatted("j.user_id"));

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
            SELECT %s, (date_trunc('week', p.fixed_at AT TIME ZONE ?))::date AS week_start, count(*)
            FROM per_job p
            JOIN import_jobs j ON j.id = p.job_id
            %s
            WHERE p.fixed_at >= ? AND p.fixed_at < ?
              AND %s
            GROUP BY 1, 2
            """.formatted(BANK, BANK_OF_JOB, CUSTOMER.formatted("j.user_id"));

    // Cell slots per bank and week.
    private static final int READ = 0, ROWS = 1, FAILED = 2, HELD = 3, FIXED = 4;

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

        // bank -> week -> cell; sorted by name for a stable tie order
        Map<String, Map<LocalDate, long[]>> byBank = new TreeMap<>();
        long[] unknown = new long[3]; // import holds, trust holds, fixes

        jdbc.query(READS, rs -> {
            long[] c = cell(byBank, rs.getString(1), rs.getObject(2, LocalDate.class));
            c[READ] += rs.getLong(3);
            c[ROWS] += rs.getLong(4);
        }, ZONE, from, to);

        jdbc.query(FAILED_READS, rs -> {
            cell(byBank, rs.getString(1), rs.getObject(2, LocalDate.class))[FAILED] += rs.getLong(3);
        }, from, to, ZONE);

        jdbc.query(HOLDS, rs -> {
            String bank = rs.getString(1);
            long count = rs.getLong(4);
            if (bank == null) {
                unknown[rs.getBoolean(3) ? 1 : 0] += count;
            } else {
                cell(byBank, bank, rs.getObject(2, LocalDate.class))[HELD] += count;
            }
        }, ZONE, from, to);

        jdbc.query(FIXES, rs -> {
            String bank = rs.getString(1);
            long count = rs.getLong(3);
            if (bank == null) {
                unknown[2] += count;
            } else {
                cell(byBank, bank, rs.getObject(2, LocalDate.class))[FIXED] += count;
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
            .comparing((ReadingQualityDto.BankRow r) -> r.problemRate() == null)
            .thenComparing(r -> r.problemRate() == null ? 0.0 : -r.problemRate())
            .thenComparing(r -> -(r.holds() + r.failedReads()))
            .thenComparing(r -> -(r.statementsRead() + r.failedReads()))
            .thenComparing(ReadingQualityDto.BankRow::bank);

    private static long[] cell(Map<String, Map<LocalDate, long[]>> byBank, String bank, LocalDate week) {
        return byBank.computeIfAbsent(bank, k -> new HashMap<>()).computeIfAbsent(week, k -> new long[5]);
    }

    private static ReadingQualityDto.BankRow row(String bank, Map<LocalDate, long[]> byWeek, List<LocalDate> weekStarts) {
        long[] total = new long[5];
        List<ReadingQualityDto.Week> weeks = new ArrayList<>();
        for (LocalDate week : weekStarts) {
            long[] c = byWeek.getOrDefault(week, new long[5]);
            weeks.add(new ReadingQualityDto.Week(week, c[READ], c[ROWS], c[FAILED], c[HELD], c[FIXED]));
            for (int i = 0; i < total.length; i++) total[i] += c[i];
        }
        long tried = total[READ] + total[FAILED];
        Double rate = tried >= MIN_STATEMENTS ? (double) (total[HELD] + total[FAILED]) / tried : null;
        return new ReadingQualityDto.BankRow(bank, total[READ], total[ROWS], total[FAILED], total[HELD], rate,
                total[FIXED], weeks);
    }
}
