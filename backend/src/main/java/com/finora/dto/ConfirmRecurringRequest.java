package com.finora.dto;

import jakarta.validation.constraints.NotBlank;

/** Mirrors DismissRecurringRequest -- merchant is RecurringDto.merchant verbatim, the only
 *  identity a detected recurring group has. See RecurringService.confirm's own doc comment for
 *  why this writes an audit entry rather than a persisted "confirmed" state. */
public record ConfirmRecurringRequest(@NotBlank String merchant) {}
