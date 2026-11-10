package com.finora.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.finora.config.WholeNumberDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Financial Intelligence Workspace, System Settings module. Deliberately just the one persisted,
 * editable field for now -- every other setting shown on the System Settings page is a static
 * "coming in a future release" label the frontend renders without calling the backend at all.
 * See V22 migration's comment and WorkspaceSettingsService's class comment for the full reasoning.
 */
public record WorkspaceSettingsDto(int autoApplyConfidenceThreshold, Instant updatedAt) {
    /**
     * {@code Integer}, not {@code int}: with a primitive, a body that left the value out (or sent
     * null) read as 0 and was saved -- and 0 means every uncategorized guess skips the review queue
     * (CategorizationService.needsCategoryReview). Now a missing value is refused by the service and
     * a fraction or a string by {@link WholeNumberDeserializer}, instead of 50.9 quietly becoming 50.
     */
    @Schema(name = "WorkspaceSettingsUpdateRequest")
    public record UpdateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "100")
            @JsonDeserialize(using = WholeNumberDeserializer.class)
            Integer autoApplyConfidenceThreshold) {}
}
