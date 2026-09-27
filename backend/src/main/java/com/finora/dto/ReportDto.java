package com.finora.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReportDto(
        String month,
        BigDecimal income,
        BigDecimal expense,
        List<CategoryAmount> categories,
        /* Credits Fynora cannot yet call income (money from a person, an unexplained credit-card
         * credit). Excluded from income, reported beside it so it is never silently dropped. */
        BigDecimal unresolvedInflow,
        /* Income split by what it was: the user's kind (Family support, their own kinds) or the
         * automatic reading (Salary, Interest, ...). Sums to income. Largest first. */
        List<IncomeLine> incomeByKind
) {
    public record CategoryAmount(String category, BigDecimal amount) {}
    public record IncomeLine(String label, BigDecimal amount) {}
}
