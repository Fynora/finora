package com.finora.activation;

import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The activation report (Gate 2a spec §6): where each signup stopped on the way to a first statement,
 * what they said when asked, and why people deleted their accounts. Counts only -- user ids are used
 * inside this class to join the tables and never leave it.
 *
 * <p>Counted: USER-scope accounts that are not marked internal and are not pending deletion or
 * deleted. Deletion reasons are kept with no link to anyone (V278), so they cannot be filtered that
 * way and are counted as stored.
 *
 * <p>The answers are the only part the date range bounds. The funnel by week, by source, the focus
 * and tracking splits and the second-import measure count every counted user, whenever they signed
 * up -- the same rule for all of them.
 */
@Service
public class ActivationFunnelService {

    static final int DEFAULT_RANGE_DAYS = 90;
    static final String NO_ANSWER = "NONE";

    /**
     * When V286 (signup_attributions) was applied to this database: the first moment a sign-up
     * source could be stored. Compared inside Postgres so that Flyway's zone-less
     * {@code installed_on} becomes an instant under the same session zone that wrote it. FACTS_SQL
     * carries the same subquery inline (a text block cannot be concatenated into).
     */
    static final String TRACKING_START_SQL =
            "SELECT max(h.installed_on)::timestamptz FROM flyway_schema_history h WHERE h.version = '286' AND h.success";

    /** One row per counted user, each fact an EXISTS over the table that records it (spec §3). */
    private static final String FACTS_SQL = """
            SELECT u.id,
                   (date_trunc('week', u.created_at AT TIME ZONE ?))::date AS week_start,
                   u.spending_tracking_method,
                   EXISTS (SELECT 1 FROM import_funnel_days f WHERE f.user_id = u.id AND f.event = 'IMPORT_SCREEN_OPENED') AS opened_import,
                   EXISTS (SELECT 1 FROM import_funnel_days f WHERE f.user_id = u.id AND f.event = 'FILE_CHOSEN') AS chose_file,
                   EXISTS (SELECT 1 FROM statement_analysis_sessions s WHERE s.user_id = u.id AND s.outcome = 'REJECTED') AS refused,
                   (EXISTS (SELECT 1 FROM statement_analysis_sessions s WHERE s.user_id = u.id AND s.outcome = 'FAILED')
                    OR EXISTS (SELECT 1 FROM import_jobs j WHERE j.user_id = u.id AND j.status = 'FAILED')) AS read_failed,
                   (EXISTS (SELECT 1 FROM import_jobs j WHERE j.user_id = u.id
                            AND j.status IN ('HELD_FOR_REVIEW', 'HELD_FOR_TRUST_REVIEW'))
                    OR EXISTS (SELECT 1 FROM held_statements h WHERE h.user_id = u.id)) AS held,
                   (EXISTS (SELECT 1 FROM statement_analysis_sessions s WHERE s.user_id = u.id AND s.outcome = 'PARSED')
                    OR EXISTS (SELECT 1 FROM import_jobs j WHERE j.user_id = u.id AND j.status = 'COMPLETED')) AS was_read,
                   EXISTS (SELECT 1 FROM import_funnel_days f WHERE f.user_id = u.id AND f.event = 'REVIEW_OPENED') AS review_opened,
                   EXISTS (SELECT 1 FROM statement_imports i WHERE i.user_id = u.id) AS imported,
                   (a.user_id IS NOT NULL) AS has_attribution,
                   EXISTS (SELECT 1 FROM referrals r WHERE r.referred_user_id = u.id) AS referred,
                   a.utm_source,
                   a.referrer_host,
                   -- No V286 row means the start is not known: everyone counts as before it, so nobody
                   -- is called "direct" whom nothing was recording.
                   COALESCE(u.created_at < (SELECT max(h.installed_on)::timestamptz FROM flyway_schema_history h
                                             WHERE h.version = '286' AND h.success), true) AS before_tracking
              FROM users u
              LEFT JOIN signup_attributions a ON a.user_id = u.id
             WHERE u.account_scope = 'USER'
               AND u.internal_account = false
               AND u.status NOT IN ('PENDING_DELETION', 'DELETED')
            """;

    /**
     * Each distinct statement file a user imported that brought in at least one transaction, with
     * when it was first imported. Grouped by the file, not by row: one composite statement writes a
     * row per account section (ImportService.confirmMultiSection) and a re-import writes another
     * row for the same file (confirmReimport), and neither is a person coming back. The file is
     * {@code content_hash} (hex SHA-256 of the upload); a row from before that column was filled is
     * hashed the same way from its inline bytes, and a row with neither stands alone. Deleted rows
     * count, as they do for GOT_DATA_IN.
     */
    private static final String IMPORTED_FILES_SQL = """
            SELECT i.user_id, min(i.imported_at) AS first_at
              FROM statement_imports i
             WHERE i.transactions_imported >= 1
             GROUP BY i.user_id, COALESCE(i.content_hash, encode(sha256(i.file_content), 'hex'), i.id::text)
            """;

    private final JdbcTemplate jdbc;
    private final ReportingDay reportingDay;

    public ActivationFunnelService(JdbcTemplate jdbc, ReportingDay reportingDay) {
        this.jdbc = jdbc;
        this.reportingDay = reportingDay;
    }

    /**
     * {@code daysToSecondImport} is null for someone without a second file. {@code cameBackLaterDay}:
     * a file first imported on a later reporting day than their first file.
     */
    record Counted(FunnelStep step, LocalDate weekStart, String tracking, SignupSources.Source source,
                   Double daysToSecondImport, boolean cameBackLaterDay) {}

    /**
     * @param from first answer date counted, inclusive; null means {@code to} minus 90 days
     * @param to   last answer date counted, inclusive; null means today in the reporting zone
     */
    @Transactional(readOnly = true)
    public ActivationReportDto report(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : reportingDay.today();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS);
        if (start.isAfter(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The start of the range is after its end.");
        }

        Map<UUID, List<OffsetDateTime>> files = new HashMap<>();
        jdbc.query(IMPORTED_FILES_SQL, rs -> {
            files.computeIfAbsent(rs.getObject("user_id", UUID.class), u -> new ArrayList<>())
                    .add(rs.getObject("first_at", OffsetDateTime.class));
        });
        ZoneId zone = reportingDay.zone();

        Map<UUID, Counted> users = new HashMap<>();
        jdbc.query(FACTS_SQL, rs -> {
            FunnelStep step = FunnelSteps.furthest(new FunnelSteps.UserFacts(
                    rs.getBoolean("opened_import"), rs.getBoolean("chose_file"), rs.getBoolean("refused"),
                    rs.getBoolean("read_failed"), rs.getBoolean("held"), rs.getBoolean("was_read"),
                    rs.getBoolean("review_opened"), rs.getBoolean("imported")));
            SignupSources.Source source = SignupSources.classify(rs.getBoolean("has_attribution"),
                    rs.getString("utm_source"), rs.getString("referrer_host"), rs.getBoolean("referred"),
                    rs.getBoolean("before_tracking"));
            UUID id = rs.getObject("id", UUID.class);
            List<OffsetDateTime> imported = files.getOrDefault(id, List.of());
            users.put(id, new Counted(step, rs.getObject("week_start", LocalDate.class),
                    rs.getString("spending_tracking_method"), source,
                    daysToSecond(imported), cameBackLaterDay(imported, zone)));
        }, reportingDay.zone().getId());

        return new ActivationReportDto(
                jdbc.queryForObject("SELECT min(funnel_date) FROM import_funnel_days", LocalDate.class),
                weeks(users),
                answers(users, start, end),
                answersByStep(users, start, end),
                focusByStep(users),
                trackingByStep(users),
                deletionReasons(),
                trackingStart(),
                bySource(users),
                secondImport(users.values()));
    }

    /** Days from the first distinct file to the second, or null with fewer than two. */
    static Double daysToSecond(List<OffsetDateTime> fileTimes) {
        if (fileTimes.size() < 2) return null;
        List<OffsetDateTime> sorted = new ArrayList<>(fileTimes);
        sorted.sort(null);
        return Duration.between(sorted.get(0), sorted.get(1)).toMillis() / (double) Duration.ofDays(1).toMillis();
    }

    /**
     * Whether any file was first imported on a later day, in the reporting zone, than the first
     * file. Two different statements sent in one sitting are a second import but not a return.
     */
    static boolean cameBackLaterDay(List<OffsetDateTime> fileTimes, ZoneId zone) {
        if (fileTimes.size() < 2) return false;
        LocalDate firstDay = null;
        LocalDate lastDay = null;
        for (OffsetDateTime t : fileTimes) {
            LocalDate day = t.atZoneSameInstant(zone).toLocalDate();
            if (firstDay == null || day.isBefore(firstDay)) firstDay = day;
            if (lastDay == null || day.isAfter(lastDay)) lastDay = day;
        }
        return lastDay.isAfter(firstDay);
    }

    private Instant trackingStart() {
        OffsetDateTime start = jdbc.queryForObject(TRACKING_START_SQL, OffsetDateTime.class);
        return start == null ? null : start.toInstant();
    }

    /**
     * One row per bucket: a row for each UTM value seen, in alphabetical order, then every fixed
     * bucket in {@link SignupSourceKind} order, zeros included -- a bucket nobody came from is a
     * finding, not a gap.
     */
    static List<ActivationReportDto.SourceRow> bySource(Map<UUID, Counted> users) {
        Map<SignupSources.Source, List<Counted>> bySource = new HashMap<>();
        for (Counted c : users.values()) {
            bySource.computeIfAbsent(c.source(), s -> new ArrayList<>()).add(c);
        }
        TreeMap<String, List<Counted>> utm = new TreeMap<>();
        bySource.forEach((source, people) -> {
            if (source.kind() == SignupSourceKind.UTM) utm.put(source.utmSource(), people);
        });
        List<ActivationReportDto.SourceRow> rows = new ArrayList<>();
        utm.forEach((value, people) -> rows.add(sourceRow(SignupSourceKind.UTM, value, people)));
        for (SignupSourceKind kind : SignupSourceKind.values()) {
            if (kind == SignupSourceKind.UTM) continue;
            rows.add(sourceRow(kind, null, bySource.getOrDefault(new SignupSources.Source(kind, null), List.of())));
        }
        return rows;
    }

    private static ActivationReportDto.SourceRow sourceRow(SignupSourceKind kind, String utmSource, List<Counted> people) {
        Map<FunnelStep, Long> counts = zeroSteps();
        for (Counted c : people) counts.merge(c.step(), 1L, Long::sum);
        return new ActivationReportDto.SourceRow(kind, utmSource, counts, secondImport(people));
    }

    static ActivationReportDto.SecondImport secondImport(Collection<Counted> people) {
        List<Double> days = new ArrayList<>();
        long laterDay = 0;
        for (Counted c : people) {
            if (c.daysToSecondImport() != null) days.add(c.daysToSecondImport());
            if (c.cameBackLaterDay()) laterDay++;
        }
        return new ActivationReportDto.SecondImport(days.size(), median(days), laterDay);
    }

    /** The median, to one decimal; the mean of the middle two for an even count; null for none. */
    static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        double mid = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        return Math.round(mid * 10) / 10.0;
    }

    private static List<ActivationReportDto.WeekRow> weeks(Map<UUID, Counted> users) {
        TreeMap<LocalDate, Map<FunnelStep, Long>> byWeek = new TreeMap<>();
        for (Counted c : users.values()) {
            byWeek.computeIfAbsent(c.weekStart(), w -> zeroSteps()).merge(c.step(), 1L, Long::sum);
        }
        // A week with no signups between the first and the last is a row of zeros: left out, a quiet
        // week would read as though it never happened.
        if (!byWeek.isEmpty()) {
            LocalDate last = byWeek.lastKey();
            for (LocalDate w = byWeek.firstKey(); w.isBefore(last); w = w.plusWeeks(1)) {
                byWeek.computeIfAbsent(w, k -> zeroSteps());
            }
        }
        List<ActivationReportDto.WeekRow> rows = new ArrayList<>();
        byWeek.forEach((week, counts) -> rows.add(new ActivationReportDto.WeekRow(week, counts)));
        return rows;
    }

    private static Map<FunnelStep, Long> zeroSteps() {
        Map<FunnelStep, Long> counts = new EnumMap<>(FunnelStep.class);
        for (FunnelStep step : FunnelStep.values()) counts.put(step, 0L);
        return counts;
    }

    private record AnswerRow(UUID userId, ActivationMoment moment, ActivationAnswer answer) {}

    /** Answers in the range from counted users only. */
    private List<AnswerRow> answerRows(Map<UUID, Counted> users, LocalDate start, LocalDate end) {
        List<AnswerRow> rows = new ArrayList<>();
        jdbc.query("SELECT user_id, moment, answer FROM activation_answers WHERE answer_date BETWEEN ? AND ?", rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            if (users.containsKey(userId)) {
                rows.add(new AnswerRow(userId, ActivationMoment.valueOf(rs.getString("moment")),
                        ActivationAnswer.valueOf(rs.getString("answer"))));
            }
        }, Date.valueOf(start), Date.valueOf(end));
        return rows;
    }

    /** Every answer each moment allows, SKIPPED included, in the order the apps show them; zeros too. */
    private List<ActivationReportDto.AnswerCount> answers(Map<UUID, Counted> users, LocalDate start, LocalDate end) {
        Map<String, Long> counts = new HashMap<>();
        for (AnswerRow row : answerRows(users, start, end)) {
            counts.merge(row.moment() + "/" + row.answer(), 1L, Long::sum);
        }
        List<ActivationReportDto.AnswerCount> out = new ArrayList<>();
        for (ActivationMoment moment : ActivationMoment.values()) {
            for (ActivationAnswer answer : ActivationAnswer.values()) {
                if (answer.allowedFor(moment)) {
                    out.add(new ActivationReportDto.AnswerCount(moment, answer,
                            counts.getOrDefault(moment + "/" + answer, 0L)));
                }
            }
        }
        return out;
    }

    private List<ActivationReportDto.StepAnswerCount> answersByStep(Map<UUID, Counted> users, LocalDate start, LocalDate end) {
        Map<List<Object>, Long> counts = new LinkedHashMap<>();
        for (AnswerRow row : answerRows(users, start, end)) {
            counts.merge(List.of(users.get(row.userId()).step(), row.moment(), row.answer()), 1L, Long::sum);
        }
        List<ActivationReportDto.StepAnswerCount> out = new ArrayList<>();
        counts.forEach((key, count) -> out.add(new ActivationReportDto.StepAnswerCount(
                (FunnelStep) key.get(0), (ActivationMoment) key.get(1), (ActivationAnswer) key.get(2), count)));
        out.sort((a, b) -> a.step() != b.step() ? a.step().compareTo(b.step())
                : a.moment() != b.moment() ? a.moment().compareTo(b.moment()) : a.answer().compareTo(b.answer()));
        return out;
    }

    /** Focus is several answers per person: each counts once under each focus chosen, or once under NONE. */
    private List<ActivationReportDto.StepLabelCount> focusByStep(Map<UUID, Counted> users) {
        Map<UUID, List<String>> focuses = new HashMap<>();
        jdbc.query("SELECT user_id, focus_key FROM user_financial_focus", rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            if (users.containsKey(userId)) {
                focuses.computeIfAbsent(userId, u -> new ArrayList<>()).add(rs.getString("focus_key"));
            }
        });
        Map<FunnelStep, Map<String, Long>> counts = new EnumMap<>(FunnelStep.class);
        users.forEach((userId, c) -> {
            for (String label : focuses.getOrDefault(userId, List.of(NO_ANSWER))) {
                counts.computeIfAbsent(c.step(), s -> new TreeMap<>()).merge(label, 1L, Long::sum);
            }
        });
        return flatten(counts);
    }

    private static List<ActivationReportDto.StepLabelCount> trackingByStep(Map<UUID, Counted> users) {
        Map<FunnelStep, Map<String, Long>> counts = new EnumMap<>(FunnelStep.class);
        for (Counted c : users.values()) {
            String label = c.tracking() != null ? c.tracking() : NO_ANSWER;
            counts.computeIfAbsent(c.step(), s -> new TreeMap<>()).merge(label, 1L, Long::sum);
        }
        return flatten(counts);
    }

    private static List<ActivationReportDto.StepLabelCount> flatten(Map<FunnelStep, Map<String, Long>> counts) {
        List<ActivationReportDto.StepLabelCount> out = new ArrayList<>();
        counts.forEach((step, labels) -> labels.forEach((label, count) ->
                out.add(new ActivationReportDto.StepLabelCount(step, label, count))));
        return out;
    }

    private List<ActivationReportDto.DeletionReasonCount> deletionReasons() {
        return jdbc.query("SELECT deletion_month, reason, count(*) AS n FROM account_deletion_reasons "
                        + "GROUP BY deletion_month, reason ORDER BY deletion_month, reason",
                (rs, i) -> new ActivationReportDto.DeletionReasonCount(rs.getObject("deletion_month", LocalDate.class),
                        DeletionReason.valueOf(rs.getString("reason")), rs.getLong("n")));
    }
}
