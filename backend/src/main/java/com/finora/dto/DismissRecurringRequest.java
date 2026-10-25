package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** merchant is RecurringDto.merchant verbatim -- the only identity a detected recurring group has,
 *  since it is recomputed fresh on every GET rather than being a persisted entity of its own. See
 *  RecurringDismissal's own doc comment. */
public record DismissRecurringRequest(
        // The dismissal's column holds 255 (V190).
        @NotBlank @Size(min = 1, max = 255, message = "Payee name can't exceed 255 characters") String merchant) {}
