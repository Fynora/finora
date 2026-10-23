package com.finora.activation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The activation page (Gate 2a spec §6): counts only. Nothing in it identifies a person -- no id,
 * email or name -- and every count is of people at their furthest step, never a list of them.
 *
 * <p>Nested records carry explicit schema names: springdoc names a schema by its simple class name,
 * so a plain "WeekRow" could collide with an unrelated class of the same name later.
 */
public record ActivationReportDto(
        /** The earliest import-funnel date, or null when the record has no rows yet. The opened-import,
         *  chose-a-file and review-opened facts exist only from this day on. */
        LocalDate funnelGoLive,
        List<WeekRow> weeks,
        List<AnswerCount> answers,
        List<StepAnswerCount> answersByStep,
        List<StepLabelCount> focusByStep,
        List<StepLabelCount> trackingByStep,
        List<DeletionReasonCount> deletionReasons,
        /** When sign-up sources began to be recorded (V286 applied), or null when that is not known.
         *  An account made before it with nothing recorded is UNKNOWN_BEFORE_TRACKING, not DIRECT. */
        Instant sourceTrackingStart,
        /** Counted users per source, at their furthest step, with the second-import measure. */
        List<SourceRow> bySource,
        /** The second-import measure over every counted user. */
        SecondImport secondImport
) {
    /** Signups in one IST week (Monday start), counted at their furthest step; every step present. */
    @Schema(name = "ActivationWeekRow")
    public record WeekRow(LocalDate weekStart, Map<FunnelStep, Long> counts) {}

    /** Answers and skips to one moment's question, in the chosen range; zeros included. */
    @Schema(name = "ActivationAnswerCount")
    public record AnswerCount(ActivationMoment moment, ActivationAnswer answer, long count) {}

    /** The same answers, split by the furthest step of the people who gave them. */
    @Schema(name = "ActivationStepAnswerCount")
    public record StepAnswerCount(FunnelStep step, ActivationMoment moment, ActivationAnswer answer, long count) {}

    /** Onboarding answers by furthest step; {@code label} is the answer's name, or "NONE". A person
     *  who chose several focuses counts once under each. */
    @Schema(name = "ActivationStepLabelCount")
    public record StepLabelCount(FunnelStep step, String label, long count) {}

    /**
     * Signups from one source at their furthest step; every step present. {@code utmSource} is the
     * tag value for a UTM row and null otherwise. {@code secondImport} counts the same people.
     */
    @Schema(name = "ActivationSourceRow")
    public record SourceRow(SignupSourceKind kind, String utmSource, Map<FunnelStep, Long> counts,
                            SecondImport secondImport) {}

    /**
     * People who imported a second, different statement file that brought in at least one
     * transaction ({@code transactions_imported >= 1}; deleted ones included, as GOT_DATA_IN
     * includes them). Files, not rows: the sections of one composite statement and a re-import of
     * the same file are one file. {@code medianDays} is the median time from the first file to the
     * second, in days to one decimal, or null when nobody has a second. {@code cameBackLaterDay}
     * counts those whose later file came on a later day (IST) than their first -- a return, not two
     * statements sent in one sitting.
     */
    @Schema(name = "ActivationSecondImport")
    public record SecondImport(long users, Double medianDays, long cameBackLaterDay) {}

    @Schema(name = "ActivationDeletionReasonCount")
    public record DeletionReasonCount(LocalDate month, DeletionReason reason, long count) {}
}
