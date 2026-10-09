package com.finora.budgets;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record BudgetDto(UUID id, UUID categoryId, String categoryName, BigDecimal monthlyLimit, BigDecimal spentThisMonth) {
    // Budgets.tsx already guards client-side against a zero/negative monthlyLimit before
    // dividing by it for the progress bar -- nothing enforced this server-side, so a
    // non-browser caller could set a budget limit of 0 or negative.
    public record UpsertRequest(
            // categories.name is VARCHAR(80); a longer name used to reach the INSERT and fail there.
            @NotBlank(message = "Category name is required")
            @Size(min = 1, max = 80, message = "Category name must be 1 to 80 characters") String categoryName,
            @NotNull(message = "Monthly limit is required") @DecimalMin(value = "0.01", message = "Monthly limit must be greater than zero") BigDecimal monthlyLimit) {}
}
