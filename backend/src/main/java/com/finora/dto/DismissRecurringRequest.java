package com.finora.dto;

import jakarta.validation.constraints.NotBlank;

/** merchant is RecurringDto.merchant verbatim -- the only identity a detected recurring group has,
 *  since it is recomputed fresh on every GET rather than being a persisted entity of its own. See
 *  RecurringDismissal's own doc comment. */
public record DismissRecurringRequest(@NotBlank String merchant) {}
