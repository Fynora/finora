package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mirrors DismissRecurringRequest -- merchant is RecurringDto.merchant verbatim, the only
 *  identity a detected recurring group has. See RecurringService.confirm's own doc comment for
 *  why this writes an audit entry rather than a persisted "confirmed" state. */
public record ConfirmRecurringRequest(
        // Same bound as DismissRecurringRequest: a payee name is no longer, and it goes into an audit row.
        @NotBlank @Size(min = 1, max = 255, message = "Payee name can't exceed 255 characters") String merchant) {}
