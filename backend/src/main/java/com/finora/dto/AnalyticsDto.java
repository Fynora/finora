package com.finora.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Backs GET /api/v1/analytics/merchants (spec §5.7) and, as of the Financial Intelligence
 *  Workspace's Analytics module, the same endpoint's topCategories/importStatistics/
 *  learningGrowth views. See AnalyticsService's class comment. */
public class AnalyticsDto {

    public record TopMerchant(UUID merchantId, String merchantName, BigDecimal totalSpend, int transactionCount) {}

    /** month is "YYYY-MM" (matches YearMonth.toString()), oldest first -- a line chart's natural x-axis order. */
    public record TrendPoint(String month, BigDecimal totalSpend) {}

    /** avgConfidence: mean confidence across every merchant whose current top category is this
     *  one. merchantCount: how many merchants that average is over -- shown alongside the bar so
     *  a category backed by 1 merchant doesn't read as equally reliable as one backed by 40. */
    public record CategoryConfidencePoint(String category, int avgConfidence, int merchantCount) {}

    /** Same shape as TopMerchant, grouped by category instead of merchant -- Workspace Analytics'
     *  "Top Categories" view. */
    public record TopCategory(UUID categoryId, String categoryName, BigDecimal totalSpend, int transactionCount) {}

    /** Workspace Analytics' "Import Statistics" view -- aggregated over StatementImport, not a
     *  new table. lastImportedAt is null when the user has never imported a statement. */
    public record ImportStatistics(int totalStatements, int totalTransactionsImported,
                                    int totalTransactionsSkipped, Instant lastImportedAt) {}

    /** Workspace Analytics' "Learning Growth" view -- LEARNED vs CORRECTED MerchantLearningAudit
     *  entries per month, oldest first (same x-axis convention as TrendPoint). CORRECTED entries
     *  matter as their own series, not just noise folded into "activity": a rising CORRECTED
     *  count month over month is a real signal the engine's guesses are getting overridden more
     *  often, not less -- the opposite of what "learning growth" should look like if it's working. */
    public record LearningGrowthPoint(String month, long learnedCount, long correctedCount) {}

    /** Multi-Year Comparison (issue #1455). coverageMonths/isComplete come from
     *  {@link com.finora.service.MultiYearCoverage.YearCoverage}. YoY deltas are a frontend/mobile
     *  concern computed from consecutive fullYears entries -- this DTO only ever carries raw,
     *  honest totals and completeness facts, never a derived comparison number. */
    public record MultiYearPoint(int year, int coverageMonths, boolean isComplete, BigDecimal total) {}

    /** One prior year's total over the SAME relative month-of-year range as
     *  {@code ThisYearSoFar.windowEndMonth} -- only years that fully cover that range appear here
     *  (see MultiYearCoverage.coversSameRelativeWindow). */
    public record ThisYearSoFarPoint(int year, BigDecimal total) {}

    /** windowEndMonth is "YYYY-MM" (the last complete month of the comparable window), or null
     *  when no month is complete yet this year -- years is empty in that case too, never a guess. */
    public record ThisYearSoFar(String windowEndMonth, List<ThisYearSoFarPoint> years) {}

    public record MultiYearReport(List<MultiYearPoint> fullYears, ThisYearSoFar thisYearSoFar) {}

    /** (total EXPENSE / total INCOME) per calendar year. ratio is null, never a guessed number,
     *  when income is zero for that year/window. */
    public record LifestyleInflationPoint(int year, int coverageMonths, boolean isComplete,
                                           BigDecimal income, BigDecimal expense, BigDecimal ratio) {}

    public record ThisYearSoFarLifestylePoint(int year, BigDecimal income, BigDecimal expense, BigDecimal ratio) {}

    public record ThisYearSoFarLifestyle(String windowEndMonth, List<ThisYearSoFarLifestylePoint> years) {}

    public record MultiYearLifestyleReport(List<LifestyleInflationPoint> fullYears, ThisYearSoFarLifestyle thisYearSoFar) {}
}
