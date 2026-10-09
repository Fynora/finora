package com.finora.dto;

import com.finora.notification.campaign.AudienceType;
import com.finora.notification.campaign.CampaignStatus;
import com.finora.notification.campaign.RunStatus;
import com.finora.notification.campaign.RunTrigger;
import com.finora.notification.campaign.ScheduleKind;
import com.finora.notification.campaign.PushCampaignSettingsStore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Admin push campaign API shapes. Times are instants (UTC on the wire) except {@code sendTimeIst},
 *  a time of day in IST, and {@code endsOn}, an IST calendar date. */
public final class PushCampaignDtos {

    private PushCampaignDtos() {
    }

    /**
     * Create or edit. Lengths mirror what fits on a phone's lock screen and V261's columns.
     * Schedule fields are validated against {@code scheduleKind} by the service: NOW_ONLY takes
     * none, ONCE_AT takes {@code runAt}, DAILY_AT takes {@code sendTimeIst} and optionally
     * {@code endsOn}.
     *
     * <p>{@code expectedVersion} is for edits only: the {@code version} the editor loaded. If someone
     * else saved the campaign since, the edit is refused (409) instead of silently overwriting their
     * change. Optional, and ignored on create.
     */
    public record PushCampaignSaveRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 240) String message,
            @NotNull AudienceType audienceType,
            @NotNull ScheduleKind scheduleKind,
            Instant runAt,
            LocalTime sendTimeIst,
            LocalDate endsOn,
            Long expectedVersion) {

        /** Without an expected version (create, or an edit that does not check). */
        public PushCampaignSaveRequest(String name, String title, String message, AudienceType audienceType,
                ScheduleKind scheduleKind, Instant runAt, LocalTime sendTimeIst, LocalDate endsOn) {
            this(name, title, message, audienceType, scheduleKind, runAt, sendTimeIst, endsOn, null);
        }
    }

    /** Who to send a test to: exactly one of {@code userId} or {@code email}. An email always means
     *  the end-user (USER) account with that email, never the admin account that may share it. */
    public record PushCampaignTestRequest(
            UUID userId,
            @Size(max = 254) String email) {
    }

    public record PushCampaignDto(
            UUID id,
            String name,
            String title,
            String message,
            AudienceType audienceType,
            ScheduleKind scheduleKind,
            Instant runAt,
            LocalTime sendTimeIst,
            LocalDate endsOn,
            CampaignStatus status,
            Instant nextRunAt,
            Instant lastTestedAt,
            UUID lastTestedBy,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    /**
     * One run. {@code sent}/{@code failed}/{@code pending}/{@code cancelled}/{@code skipped} are read
     * live from the outbox for the run's IST day, so they keep moving while the dispatcher works;
     * {@code null} when not looked up (a MISSED run sent nothing). {@code skipped} are people with no
     * working device left (app uninstalled): routine, not a failure.
     */
    public record PushCampaignRunDto(
            UUID id,
            UUID campaignId,
            long campaignVersion,
            LocalDate runDateIst,
            Instant scheduledFor,
            RunTrigger triggeredBy,
            UUID triggeredByUser,
            Instant startedAt,
            Instant finishedAt,
            RunStatus status,
            int audienceSize,
            int queuedCount,
            int skippedCapCount,
            int skippedAlreadyQueuedCount,
            String titleSnapshot,
            String messageSnapshot,
            AudienceType audienceSnapshot,
            String note,
            Long sent,
            Long failed,
            Long pending,
            Long cancelled,
            Long skipped) {
    }

    /** What an emergency cancel did: queued pushes withdrawn, and daily slots given back. */
    public record PushCampaignCancelResultDto(long cancelledPushes, long releasedSlots) {
    }

    public record PushCampaignDetailDto(PushCampaignDto campaign, List<PushCampaignRunDto> runs) {
    }

    /** "Current estimated audience": the real number can differ by send time. */
    public record PushCampaignAudienceCountDto(AudienceType audienceType, long count, long rolloutLimit) {
    }

    /**
     * How many campaign pushes one person may get per IST day, across all campaigns. The bounds are
     * validated here and again by the table's CHECK constraint.
     */
    public record PushCampaignSettingsRequest(
            @Min(PushCampaignSettingsStore.MIN_DAILY_LIMIT) @Max(PushCampaignSettingsStore.MAX_DAILY_LIMIT)
            int dailyLimitPerPerson) {
    }

    /** The current settings, with the bounds the editor should offer. */
    public record PushCampaignSettingsDto(
            int dailyLimitPerPerson,
            int minDailyLimit,
            int maxDailyLimit,
            Instant updatedAt,
            UUID updatedBy) {
    }

    /** {@code queued} false means nothing was sent; {@code detail} says why in plain words. */
    public record PushCampaignTestResultDto(boolean queued, String detail) {
    }
}
