package com.finora.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Festival app icon: the public "which icon now" answer and the admin schedule surface. */
public final class AppIconDtos {

    private AppIconDtos() {
    }

    /**
     * What GET /api/v1/app/icon answers. {@code until} is the instant the scheduled icon stops
     * applying, and is left out entirely for the default icon, which has no end.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppIconDto(String icon, Instant until) {
    }

    /** One icon the admin may pick. Only schedulable icons are offered; the default never needs a window. */
    public record AppIconOptionDto(String key, String label) {
    }

    /** Where a window stands at the moment it was read. */
    public enum AppIconScheduleState { UPCOMING, ACTIVE, ENDED }

    /**
     * One schedule window for the admin table. {@code iconLabel} is null when the stored key is no
     * longer in the code's list (the public endpoint shows the default icon for such a row).
     * The two emails are null when that admin account no longer exists.
     */
    public record AppIconScheduleDto(
            UUID id,
            String icon,
            String iconLabel,
            String label,
            Instant startsAt,
            Instant endsAt,
            AppIconScheduleState state,
            UUID createdBy,
            String createdByEmail,
            Instant createdAt,
            UUID updatedBy,
            String updatedByEmail,
            Instant updatedAt,
            long version) {
    }

    /**
     * Create or update a window. {@code expectedVersion} is required on update and ignored on create:
     * if another admin saved the row in between, the update is refused with 409 instead of
     * overwriting their change.
     */
    public record AppIconScheduleSaveRequest(
            @NotBlank @Size(max = 32) String icon,
            @NotBlank @Size(max = 80) String label,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            Long expectedVersion) {
    }
}
