package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A saved recurring-payment answer whose payee's latest payment is outside the saved amount range,
 * for a payee the recurring detector no longer groups (a rent rise above 20% breaks its
 * amount-consistency test). The app asks "still {@code category}?" about it.
 */
public record ChangedAmountDto(
        String merchant,
        String category,
        BigDecimal latestAmount,
        LocalDate latestDate,
        BigDecimal amountMin,
        BigDecimal amountMax) {}
