package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One Insights row the user marked "Got it": its kind and key (see V276 for each kind's key). */
public record InsightAcknowledgementDto(
        @NotNull @Pattern(regexp = "CATEGORY_HIGH|AMOUNT_CHANGED|CARD_STATEMENT_MISSING") String kind,
        @NotBlank @Size(max = 300) String itemKey) {}
