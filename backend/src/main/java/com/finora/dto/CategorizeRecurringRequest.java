package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The user's answer to the recurring-payment question: what a repeating payment to {@code merchant}
 * (RecurringDto.merchant verbatim, the payee label) is for. {@code category} is a category name; a
 * name the user does not have yet is created, since choosing it is an explicit decision.
 */
public record CategorizeRecurringRequest(
        @NotBlank @Size(max = 255) String merchant,
        @NotBlank @Size(max = 100) String category) {}
