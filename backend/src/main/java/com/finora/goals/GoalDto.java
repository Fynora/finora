package com.finora.goals;

import com.finora.validation.FourDigitYear;
import com.finora.validation.MoneyLimits;
import com.finora.validation.WholePaise;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record GoalDto(UUID id, String name, BigDecimal targetAmount, BigDecimal currentAmount, LocalDate targetDate) {

    // Matches NUMERIC(14,2). A larger value used to reach the database, fail there, and come back
    // as a 409 "refresh and try again"; a huge exponent ("1e999999999") is rejected here too,
    // before any arithmetic. A third decimal place (see WholePaise) was silently rounded by the
    // column, so the response disagreed with what was stored. The limits are shared with every
    // other money request -- see MoneyLimits.
    private static final String MAX_AMOUNT_TEXT = MoneyLimits.MAX_AMOUNT_TEXT;
    /** The largest amount the goals table can hold: every amount column there is NUMERIC(14,2). */
    public static final BigDecimal MAX_AMOUNT = MoneyLimits.MAX_AMOUNT;
    private static final String AMOUNT_TOO_LARGE = MoneyLimits.TOO_LARGE;
    private static final String FINER_THAN_PAISA = MoneyLimits.FINER_THAN_PAISA;

    // The frontend (Goals.tsx) already guards against a non-positive target amount and blank
    // name client-side, but nothing enforced this server-side -- any other API caller could
    // create a goal with a zero/negative target or a blank name. currentAmount is intentionally
    // left nullable (a brand-new goal legitimately starts with none saved yet) but still can't
    // be negative if provided. name is capped at the column's VARCHAR(120) -- counted in UTF-16
    // units, so a name heavy in emoji is refused a little before the column would refuse it, never
    // after -- and refuses control characters: Postgres cannot store U+0000 at all (it failed in
    // the database as a 409), and a line break or tab has no place in a one-line name.
    @Schema(name = "GoalCreateRequest")
    public record CreateRequest(
            @NotBlank(message = "Goal name is required")
            // min = 1 restates @NotBlank for the published spec: springdoc lets @Size's default
            // min of 0 replace the minLength of 1 it derives from @NotBlank.
            @Size(min = 1, max = 120, message = "Goal name can be at most 120 characters")
            @Pattern(regexp = "[^\\x00-\\x1F\\x7F]*", message = "Goal name can't contain line breaks, tabs or other control characters") String name,
            @NotNull(message = "Target amount is required")
            @DecimalMin(value = "0.01", message = "Target amount must be greater than zero")
            @DecimalMax(value = MAX_AMOUNT_TEXT, message = "Target amount" + AMOUNT_TOO_LARGE)
            @WholePaise(message = "Target amount" + FINER_THAN_PAISA) BigDecimal targetAmount,
            @DecimalMin(value = "0.00", message = "Starting amount can't be negative")
            @DecimalMax(value = MAX_AMOUNT_TEXT, message = "Starting amount" + AMOUNT_TOO_LARGE)
            @WholePaise(message = "Starting amount" + FINER_THAN_PAISA) BigDecimal currentAmount,
            @FourDigitYear(message = "Target date must be between 0001-01-01 and 9999-12-31") LocalDate targetDate) {}

    // A contribution of zero or a negative amount would silently corrupt a goal's currentAmount
    // (see GoalService.addContribution's own floor-at-zero defense-in-depth for the belt-and-
    // braces half of this fix).
    public record ContributionRequest(
            @NotNull(message = "Contribution amount is required")
            @DecimalMin(value = "0.01", message = "Contribution amount must be greater than zero")
            @DecimalMax(value = MAX_AMOUNT_TEXT, message = "Contribution amount" + AMOUNT_TOO_LARGE)
            @WholePaise(message = "Contribution amount" + FINER_THAN_PAISA) BigDecimal amount) {}
}
