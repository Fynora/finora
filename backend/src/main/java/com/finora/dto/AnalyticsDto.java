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

    /** One slice of the dashboard's spend-by-category breakdown: grouped by category NAME, with
     *  spend that has no (or a since-deleted) category under "Uncategorized", and uncapped -- see
     *  {@code AnalyticsService#categoryBreakdown}. No id, because a name-grouped slice can span
     *  several category rows. */
    public record CategorySpend(String categoryName, BigDecimal totalSpend, int transactionCount) {}

    /**
     * Advanced Reports' "International spend" card -- every transaction its own statement printed
     * under an "International Transactions" heading, over the same expense set Top Categories uses
     * (live accounts, refunds netted, credit-card bill payments excluded). All rupee figures are
     * what was billed.
     *
     * @param purchasesSpend      rows that printed a foreign amount beside the rupee amount
     * @param otherChargesSpend   international rows that printed none. On the real evidencing
     *                            statement these are the IGST on each purchase and the FX markup
     *                            fee -- but a merchant abroad that bills in rupees prints no
     *                            foreign amount either, so this is "everything else", deliberately
     *                            never labelled as fees
     * @param byCurrency          purchases grouped by the currency they were printed in, largest
     *                            rupee total first
     */
    public record InternationalSpend(BigDecimal totalSpend, int transactionCount,
                                     BigDecimal purchasesSpend, BigDecimal otherChargesSpend,
                                     List<CurrencySpend> byCurrency) {}

    /** {@code foreignTotal} is the sum of the printed foreign amounts, {@code rupeeTotal} what
     *  those same purchases were billed in rupees. */
    public record CurrencySpend(String currency, BigDecimal foreignTotal, BigDecimal rupeeTotal,
                                int transactionCount) {}

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

    /** Category evolution's per-year breakdown -- same category/spend pairing as TopCategory,
     *  without transactionCount (not part of this view). */
    public record CategoryYearBreakdown(UUID categoryId, String categoryName, BigDecimal totalSpend) {}

    public record MultiYearCategoryPoint(int year, int coverageMonths, boolean isComplete,
                                          List<CategoryYearBreakdown> categories) {}

    public record ThisYearSoFarCategoryPoint(int year, List<CategoryYearBreakdown> categories) {}

    public record ThisYearSoFarCategories(String windowEndMonth, List<ThisYearSoFarCategoryPoint> years) {}

    public record MultiYearCategoryReport(List<MultiYearCategoryPoint> fullYears, ThisYearSoFarCategories thisYearSoFar) {}
}
