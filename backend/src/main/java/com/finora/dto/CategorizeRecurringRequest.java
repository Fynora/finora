package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The user's answer to the recurring-payment question: what a repeating payment to {@code merchant}
 * (RecurringDto.merchant verbatim, the payee label) is for. {@code category} is a category name; a
 * name the user does not have yet is created, since choosing it is an explicit decision.
 */
public record CategorizeRecurringRequest(
        @NotBlank @Size(min = 1, max = 255, message = "Payee name can't exceed 255 characters") String merchant,
        // categories.name holds 80: a longer name was cut to 80 without a word.
        @NotBlank @Size(min = 1, max = 80, message = "Category name can't exceed 80 characters") String category) {}
