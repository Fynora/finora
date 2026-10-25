package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NetWorthDto(
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal netWorth,
        List<SnapshotPoint> history,
        // Credit cards left out of totalLiabilities and netWorth because nothing ever stated what
        // is owed on them (see AmountOwedRule). Same meaning as DashboardSummaryDto's field.
        int cardsWithUnknownAmountOwed
) {
    public record SnapshotPoint(LocalDate date, BigDecimal netWorth) {}
}
