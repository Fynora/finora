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
        /** V300: the first public page each sign-up landed on, with how many got data in. Aggregates
         *  only; counts accounts made after landing paths began to be recorded. */
        LandingPaths landingPaths,
        /** The second-import measure over every counted user. */
        SecondImport secondImport,
        /** Gate 2b spec §6: month-start nudges queued per month (IST), oldest first. */
        List<NudgeMonth> monthStartNudges,
        /** How many of every counted user reached each step of the main path, with the drop from the
         *  step before. The same people as {@code weeks}, summed. */
        Reached reached,
        /** V303: release markers, oldest first, each with the signups either side of it compared. */
        List<ReleaseMarker> releaseMarkers,
        /** V315: the invite-a-friend card after an import, in people. */
        InvitePrompt invitePrompt
) {
    /**
     * The invite-a-friend card after an import (V315), counted users only (USER scope, not
     * internal, not deleted): how many people were shown it, how many tapped Share, and how many
     * tapped Not now (one person can be in both, over two showings). People, never who. Sign-ups
     * that came through a shared web link show in {@code bySource} as utm_source friend_invite.
     */
    @Schema(name = "ActivationInvitePrompt")
    public record InvitePrompt(long shown, long shareTapped, long notNow) {}

    /**
     * A dated event an admin marked on the page, such as "Gate 2b live". {@code before} and
     * {@code after} compare the signups either side over windows of the same number of days:
     * {@code after} runs from the marker's day to today, {@code before} the same number of days up to
     * the day before it. Both are null while the marker's day is still to come.
     */
    @Schema(name = "ActivationReleaseMarker")
    public record ReleaseMarker(long id, LocalDate date, String label, MarkerWindow before, MarkerWindow after) {}

    /**
     * The counted signups whose signup day (IST) falls from {@code from} to {@code to}, inclusive, by
     * each main-path step they reached, every step present ({@code reached.GOT_DATA_IN} is how many
     * got data in), and how many of them came back another day.
     */
    @Schema(name = "ActivationMarkerWindow")
    public record MarkerWindow(LocalDate from, LocalDate to, long signups, Map<ReachedStep, Long> reached,
                               long cameBackLaterDay) {}

    /**
     * Counted signups by the furthest step of the main path they reached ({@link ReachedStep}), in
     * path order. {@code signups} is everyone counted; {@code stoppedAt} is the same people at their
     * furthest step, every step present, so a drop can be split into where those people stopped.
     */
    @Schema(name = "ActivationReached")
    public record Reached(long signups, List<ReachedRow> steps, Map<FunnelStep, Long> stoppedAt) {}

    /**
     * One step of the main path. {@code reached}: signups whose furthest step ranks at or above it.
     * {@code lost}: those who reached the step before but not this one (0 for the first step).
     * {@code lostBeforeFunnelRecord}: of {@code lost}, the people who signed up before the
     * import-funnel record began, for a step known only from that record -- they may have reached it
     * unrecorded, so the drop may be smaller than shown. 0 for every other step.
     */
    @Schema(name = "ActivationReachedRow")
    public record ReachedRow(ReachedStep step, long reached, long lost, long lostBeforeFunnelRecord) {}

    /**
     * Month-start nudges queued in one IST month, to counted users only (USER scope, not internal,
     * not deleted), and how many of those nudged imported a statement within 3 days after it.
     */
    @Schema(name = "ActivationNudgeMonth")
    public record NudgeMonth(LocalDate month, long nudgesSent, long importedWithin3Days) {}

    /**
     * Signups in one IST week (Monday start), counted at their furthest step; every step present.
     * {@code reached} counts the same people by each main-path step they reached, every step present.
     * {@code cameBackLaterDay}: of them, how many sent a second, different file on a later day (IST)
     * than their first -- the measure in {@link SecondImport}, by signup week.
     */
    @Schema(name = "ActivationWeekRow")
    public record WeekRow(LocalDate weekStart, Map<FunnelStep, Long> counts, Map<ReachedStep, Long> reached,
                          long cameBackLaterDay) {}

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
     * Sign-ups by the first public page of their visit (V300), for accounts made after
     * {@code trackingStart} (when landing paths began to be recorded; null when that is not known,
     * in which case every list is empty). {@code pages} are the busiest paths, most sign-ups first;
     * {@code other} sums the paths beyond them; {@code none} counts accounts with no landing path
     * recorded -- a visit that began off the public pages, a typed-in /auth, the mobile apps.
     */
    @Schema(name = "ActivationLandingPaths")
    public record LandingPaths(Instant trackingStart, List<LandingPathRow> pages, LandingPathRow other,
                               LandingPathRow none) {}

    /**
     * One landing path: how many counted accounts started on it, how many of those got data in
     * (reached {@link FunnelStep#GOT_DATA_IN}) and how many came back another day (the measure in
     * {@link SecondImport}). {@code path} is null for the {@code other} and {@code none} rows.
     */
    @Schema(name = "ActivationLandingPathRow")
    public record LandingPathRow(String path, long signups, long gotDataIn, long cameBackLaterDay) {}

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
