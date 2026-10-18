package com.finora.activation;

import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
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
 */
@Service
public class ActivationFunnelService {

    static final int DEFAULT_RANGE_DAYS = 90;
    static final String NO_ANSWER = "NONE";

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
                   EXISTS (SELECT 1 FROM statement_imports i WHERE i.user_id = u.id) AS imported
              FROM users u
             WHERE u.account_scope = 'USER'
               AND u.internal_account = false
               AND u.status NOT IN ('PENDING_DELETION', 'DELETED')
            """;

    private final JdbcTemplate jdbc;
    private final ReportingDay reportingDay;

    public ActivationFunnelService(JdbcTemplate jdbc, ReportingDay reportingDay) {
        this.jdbc = jdbc;
        this.reportingDay = reportingDay;
    }

    private record Counted(FunnelStep step, LocalDate weekStart, String tracking) {}

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

        Map<UUID, Counted> users = new HashMap<>();
        jdbc.query(FACTS_SQL, rs -> {
            FunnelStep step = FunnelSteps.furthest(new FunnelSteps.UserFacts(
                    rs.getBoolean("opened_import"), rs.getBoolean("chose_file"), rs.getBoolean("refused"),
                    rs.getBoolean("read_failed"), rs.getBoolean("held"), rs.getBoolean("was_read"),
                    rs.getBoolean("review_opened"), rs.getBoolean("imported")));
            users.put(rs.getObject("id", UUID.class),
                    new Counted(step, rs.getObject("week_start", LocalDate.class), rs.getString("spending_tracking_method")));
        }, reportingDay.zone().getId());

        return new ActivationReportDto(
                jdbc.queryForObject("SELECT min(funnel_date) FROM import_funnel_days", LocalDate.class),
                weeks(users),
                answers(users, start, end),
                answersByStep(users, start, end),
                focusByStep(users),
                trackingByStep(users),
                deletionReasons());
    }

    private static List<ActivationReportDto.WeekRow> weeks(Map<UUID, Counted> users) {
        Map<LocalDate, Map<FunnelStep, Long>> byWeek = new TreeMap<>();
        for (Counted c : users.values()) {
            byWeek.computeIfAbsent(c.weekStart(), w -> zeroSteps()).merge(c.step(), 1L, Long::sum);
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
