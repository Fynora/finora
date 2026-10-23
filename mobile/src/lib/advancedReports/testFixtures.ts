import type { AdvancedMonth, AdvancedReport } from '../../api/endpoints';

/** A month of the fixture: income 100,000, expenses 40,000, whole. */
export const fixtureMonth = (month: string, over: Partial<AdvancedMonth> = {}): AdvancedMonth => ({
  month, income: 100000, expense: 40000, net: 60000, savingsRatePct: 60, complete: true, transactionCount: 12, ...over,
});

/** A complete, reliable report for Apr-Sep 2026 against Oct 2025-Mar 2026, for tests to override. */
export function fixtureReport(over: Partial<AdvancedReport> = {}): AdvancedReport {
  const months = ['2026-04', '2026-05', '2026-06', '2026-07', '2026-08', '2026-09'].map((m) => fixtureMonth(m));
  return {
    period: {
      hasData: true, noDataReason: null, range: 'LAST_6_MONTHS', startMonth: '2026-04', endMonth: '2026-09', monthCount: 6,
      requestedMonths: 6, firstDataMonth: '2025-01', newestDataMonth: '2026-09', leftOutMonth: null, leftOutReason: null,
      incompleteMonths: [], compare: 'PREVIOUS_PERIOD', compareOptions: ['PREVIOUS_PERIOD', 'SAME_PERIOD_LAST_YEAR'],
      previousStartMonth: '2025-10', previousEndMonth: '2026-03',
    },
    totals: {
      income: 600000, expense: 240000, net: 360000, avgMonthlyExpense: 40000, monthsWithData: 6, savingsRatePct: 60,
      savingsRateGateReason: null, unresolvedInflow: 0, unresolvedInflowCount: 0, transactionCount: 72,
      highestSpendMonth: '2026-07', lowestSpendMonth: '2026-04',
    },
    comparison: {
      reliable: true, gateReason: null, previousIncome: 540000, previousExpense: 200000, previousNet: 340000,
      previousAvgMonthlyExpense: 33333, previousSavingsRatePct: 63, incomeDeltaPct: 11.1, expenseDeltaPct: 20,
      netDelta: 20000, avgMonthlyExpenseDeltaPct: 20, savingsRateDeltaPp: -3,
    },
    months,
    categories: {
      rows: [
        { name: 'Rent', amount: 120000, sharePct: 50, transactionCount: 6, previousAmount: 120000, changeAmount: 0, changePct: 0, monthly: [20000, 20000, 20000, 20000, 20000, 20000] },
        { name: 'Food & Dining', amount: 60000, sharePct: 25, transactionCount: 40, previousAmount: 40000, changeAmount: 20000, changePct: 50, monthly: [8000, 9000, 10000, 11000, 11000, 11000] },
        { name: 'Shopping', amount: 30000, sharePct: 12.5, transactionCount: 9, previousAmount: 40000, changeAmount: -10000, changePct: -25, monthly: [8000, 6000, 5000, 4000, 4000, 3000] },
        { name: 'Travel', amount: 12000, sharePct: 5, transactionCount: 2, previousAmount: 0, changeAmount: 12000, changePct: null, monthly: [0, 0, 0, 12000, 0, 0] },
      ],
      otherAmount: 18000, otherSharePct: 7.5, categoryCount: 7,
    },
    merchants: {
      rows: [
        { merchantId: 'm1', name: 'Amazon', amount: 30000, sharePct: 12.5, transactionCount: 9, previousAmount: 20000, changeAmount: 10000, changePct: 50, monthly: [5000, 5000, 5000, 5000, 5000, 5000], logoDomain: 'amazon.in' },
        { merchantId: 'm2', name: 'Swiggy', amount: 24000, sharePct: 10, transactionCount: 30, previousAmount: 24000, changeAmount: 0, changePct: 0, monthly: [4000, 4000, 4000, 4000, 4000, 4000], logoDomain: 'swiggy.com' },
      ],
      attributedSharePct: 62, topFiveSharePct: 45, merchantCount: 12,
    },
    paymentMethods: [{ method: 'UPI', amount: 140000 }, { method: 'CARD', amount: 80000 }, { method: 'UNIDENTIFIED', amount: 20000 }],
    income: {
      sources: [{ label: 'Salary', amount: 540000, sharePct: 90, previousAmount: 480000 }, { label: 'Interest', amount: 60000, sharePct: 10, previousAmount: 60000 }],
      monthsWithIncome: 6, highestMonth: '2026-09', highestMonthAmount: 110000, lowestMonth: '2026-04', lowestMonthAmount: 95000, lowestAsPctOfHighest: 86.4,
    },
    unusualSpends: [],
    international: { totalSpend: 0, transactionCount: 0, purchasesSpend: 0, otherChargesSpend: 0, byCurrency: [] },
    insights: [
      {
        id: 'SPENDING_CHANGE', kind: 'SPENDING_CHANGE', tone: 'UP', headline: 'Your spending is 20% higher',
        detail: 'You spent ₹40,000 more than in the previous 6 months: ₹2,40,000 against ₹2,00,000.',
        drill: { from: '2026-04-01', to: '2026-09-30', categoryName: null, keyword: null },
      },
      {
        id: 'CATEGORY_DOWN', kind: 'CATEGORY_DOWN', tone: 'DOWN', headline: 'Shopping is lower',
        detail: 'You spent ₹30,000 on Shopping, ₹10,000 (25%) less than in the previous 6 months.',
        drill: { from: '2026-04-01', to: '2026-09-30', categoryName: 'Shopping', keyword: null },
      },
    ],
    ...over,
  };
}
