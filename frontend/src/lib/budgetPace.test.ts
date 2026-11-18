import { describe, expect, it } from 'vitest';
import {
  budgetPace,
  dayCount,
  describePace,
  groupBudgets,
  limitProblem,
  monthClock,
  overallPace,
  overallSummary,
  paceA11yLabel,
  parseLimit,
  replaceLimitNote,
  spentPercent,
  withSavedBudget,
  GROUP_LABEL,
} from './budgetPace';

const clock = monthClock(new Date(2026, 10, 17));
const money = (n: number) => '₹' + n.toLocaleString('en-IN');

const SAMPLE = [
  { categoryName: 'Entertainment', spentThisMonth: 600, monthlyLimit: 2000 },
  { categoryName: 'Fuel', spentThisMonth: 2000, monthlyLimit: 4000 },
  { categoryName: 'Food and dining', spentThisMonth: 7200, monthlyLimit: 9000 },
  { categoryName: 'Groceries', spentThisMonth: 4500, monthlyLimit: 8000 },
  { categoryName: 'Shopping', spentThisMonth: 8400, monthlyLimit: 6000 },
];

describe('monthClock', () => {
  it('reads 17 Nov 2026 as day 17 of 30 with 13 left', () => {
    expect(clock).toMatchObject({
      dayOfMonth: 17, daysInMonth: 30, daysLeft: 13, monthEndLabel: '30 Nov', monthLabel: 'November 2026',
    });
    expect(clock.monthFrac).toBeCloseTo(17 / 30);
  });

  it('handles a leap February', () => {
    expect(monthClock(new Date(2028, 1, 29))).toMatchObject({ daysInMonth: 29, daysLeft: 0 });
  });
});

describe('budgetPace', () => {
  it('food 7,200 of 9,000 on day 17 is heading over', () => {
    expect(budgetPace(7200, 9000, clock)).toMatchObject({
      safePerDay: 138, forecast: 12706, forecastOverBy: 3706, overBy: 0, group: 'heading-over',
    });
  });

  it('shopping 8,400 of 6,000 is over with nothing safe', () => {
    expect(budgetPace(8400, 6000, clock)).toMatchObject({ overBy: 2400, safePerDay: 0, remaining: -2400, group: 'over' });
  });

  it('groceries 4,500 of 8,000 is on track', () => {
    expect(budgetPace(4500, 8000, clock)).toMatchObject({ safePerDay: 269, forecast: 7941, forecastOverBy: 0, group: 'on-track' });
  });

  it('spent exactly the limit counts as over', () => {
    expect(budgetPace(9000, 9000, clock).group).toBe('over');
  });

  it('forecast exactly on the limit stays on track', () => {
    expect(budgetPace(100, 3000, monthClock(new Date(2026, 10, 1))).group).toBe('on-track');
  });

  it('nothing spent: forecast 0, on track, full limit spread over days left', () => {
    expect(budgetPace(0, 1300, clock)).toMatchObject({ forecast: 0, safePerDay: 100, spentFrac: 0, group: 'on-track' });
  });

  it('last day: safePerDay 0, never Infinity', () => {
    const p = budgetPace(1000, 2800, monthClock(new Date(2026, 10, 30)));
    expect(p.safePerDay).toBe(0);
    expect(p.remaining).toBe(1800);
  });
});

describe('overallPace', () => {
  it('sums every budget: 22,700 of 29,000', () => {
    expect(overallPace(SAMPLE, clock)).toMatchObject({ safePerDay: 484, forecast: 40059, forecastOverBy: 11059, group: 'heading-over' });
  });

  it('with no budgets reads as nothing spent, on track', () => {
    expect(overallPace([], clock)).toMatchObject({ forecast: 0, safePerDay: 0, spentFrac: 0, group: 'on-track' });
  });
});

describe('groupBudgets', () => {
  it('groups problems first and sorts inside each group', () => {
    const g = groupBudgets(SAMPLE, clock);
    expect(g.map((x) => x.group)).toEqual(['over', 'heading-over', 'on-track']);
    expect(g[0].items.map((i) => i.budget.categoryName)).toEqual(['Shopping']);
    expect(g[1].items.map((i) => i.budget.categoryName)).toEqual(['Food and dining']);
    expect(g[2].items.map((i) => i.budget.categoryName)).toEqual(['Groceries', 'Fuel', 'Entertainment']);
  });

  it('omits groups with no budgets', () => {
    expect(groupBudgets([SAMPLE[3]], clock).map((x) => x.group)).toEqual(['on-track']);
    expect(groupBudgets([], clock)).toEqual([]);
  });

  it('sorts over budgets by amount over, largest first', () => {
    const g = groupBudgets([
      { categoryName: 'A', spentThisMonth: 1100, monthlyLimit: 1000 },
      { categoryName: 'B', spentThisMonth: 5000, monthlyLimit: 1000 },
    ], clock);
    expect(g[0].items.map((i) => i.budget.categoryName)).toEqual(['B', 'A']);
  });

  it('sorts heading-over budgets by forecast overshoot, largest first', () => {
    const g = groupBudgets([
      { categoryName: 'Small', spentThisMonth: 600, monthlyLimit: 1000 },
      { categoryName: 'Big', spentThisMonth: 9000, monthlyLimit: 10000 },
    ], clock);
    expect(g.map((x) => x.group)).toEqual(['heading-over']);
    expect(g[0].items.map((i) => i.budget.categoryName)).toEqual(['Big', 'Small']);
  });
});

describe('describePace', () => {
  it('describes each state in the agreed words', () => {
    expect(describePace(budgetPace(8400, 6000, clock), clock, money)).toEqual({ main: '₹2,400 over', sub: '₹0 left to spend' });
    expect(describePace(budgetPace(7200, 9000, clock), clock, money)).toEqual({ main: '₹138 / day safe', sub: 'Heading ₹3,706 over by 30 Nov' });
    expect(describePace(budgetPace(4500, 8000, clock), clock, money)).toEqual({ main: '₹269 / day safe', sub: 'About ₹7,941 by 30 Nov' });
  });

  it('reads Last day on the final day of the month', () => {
    const last = monthClock(new Date(2026, 10, 30));
    expect(describePace(budgetPace(1000, 2800, last), last, money)).toEqual({ main: 'Last day · ₹1,800 left', sub: '' });
  });

  it('says nothing is spent yet instead of "About ₹0"', () => {
    expect(describePace(budgetPace(0, 3000, clock), clock, money)).toEqual({ main: '₹230 / day safe', sub: 'Nothing spent yet' });
  });

  it('an over budget on the last day still reads as over', () => {
    const last = monthClock(new Date(2026, 10, 30));
    expect(describePace(budgetPace(3000, 2800, last), last, money).main).toBe('₹200 over');
  });
});

describe('paceA11yLabel', () => {
  it('reads a heading-over budget with its daily amount and forecast', () => {
    expect(paceA11yLabel('Food and dining', 7200, 9000, budgetPace(7200, 9000, clock), clock, money))
      .toBe('Food and dining: ₹7,200 spent of ₹9,000. ₹138 a day safe for 13 days. Heading ₹3,706 over by 30 Nov.');
  });

  it('reads an over budget', () => {
    expect(paceA11yLabel('Shopping', 8400, 6000, budgetPace(8400, 6000, clock), clock, money))
      .toBe('Shopping: ₹8,400 spent of ₹6,000. ₹2,400 over budget.');
  });

  it('reads the last day', () => {
    const last = monthClock(new Date(2026, 10, 30));
    expect(paceA11yLabel('Fuel', 1000, 2800, budgetPace(1000, 2800, last), last, money))
      .toBe('Fuel: ₹1,000 spent of ₹2,800. Last day, ₹1,800 left.');
  });
});

describe('GROUP_LABEL', () => {
  it('names the three groups', () => {
    expect(GROUP_LABEL).toEqual({ over: 'Over', 'heading-over': 'Heading over at this pace', 'on-track': 'On track' });
  });
});

describe('wording at the edges', () => {
  const rupees = (n: number) => '₹' + Math.round(n).toLocaleString('en-IN');
  const dayBeforeLast = monthClock(new Date(2026, 10, 29));

  it('says 1 day, not 1 days', () => {
    expect(dayCount(1)).toBe('1 day');
    expect(dayCount(13)).toBe('13 days');
    const p = budgetPace(100, 1000, dayBeforeLast);
    expect(paceA11yLabel('Fuel', 100, 1000, p, dayBeforeLast, rupees)).toContain('₹900 a day safe for 1 day.');
    expect(overallSummary(100, 1000, p, dayBeforeLast, rupees)).toContain('₹900 a day safe for 1 day.');
  });

  it.each([[5000, 5000], [5000.4, 5000]])('spent %d of %d reads as limit reached, never ₹0 over', (spent, limit) => {
    const p = budgetPace(spent, limit, clock);
    expect(p.group).toBe('over');
    expect(describePace(p, clock, rupees).main).toBe('Limit reached');
    expect(paceA11yLabel('Rent', spent, limit, p, clock, rupees)).toContain('Limit reached.');
    expect(overallSummary(spent, limit, p, clock, rupees)).toContain('All of your budgets used.');
  });

  it('still names the amount from one rupee over', () => {
    expect(describePace(budgetPace(5001, 5000, clock), clock, rupees).main).toBe('₹1 over');
  });

  it('caps a runaway percentage so it cannot overflow the ring', () => {
    expect(spentPercent(0.781)).toBe('78%');
    expect(spentPercent(9.99)).toBe('999%');
    expect(spentPercent(100000)).toBe('999%+');
  });
});

describe('parseLimit', () => {
  it.each([
    ['5000', 5000], [' 5000 ', 5000], ['10,000', 10000], ['1,25,000', 125000], ['125,000', 125000],
    ['99.50', 99.5], ['5.', 5], ['.5', 0.5], ['2,60,000', 260000], ['260,000', 260000], ['1,000,000', 1000000],
    ['2,60,000.50', 260000.5],
  ])('reads %j as %d', (typed, expected) => {
    expect(parseLimit(typed)).toBe(expected);
  });

  it.each(['', '   ', '0', '0.00', '-5', 'abc', '12abc', '1e5', '0x10', 'Infinity', '12,5', '1,2,3', ',500', '5,000,', '1.2.3'])(
    'refuses %j', (typed) => {
      expect(parseLimit(typed)).toBeNull();
    },
  );

  it.each(['12,50', '1,00', '1,000,00'])('refuses %j rather than saving a wrong amount', (typed) => {
    expect(parseLimit(typed)).toBeNull();
  });
});

describe('withSavedBudget', () => {
  const a = { id: 'a', monthlyLimit: 100 };
  const b = { id: 'b', monthlyLimit: 200 };

  it('replaces the saved budget where it is', () => {
    expect(withSavedBudget([a, b], { id: 'a', monthlyLimit: 900 })).toEqual([{ id: 'a', monthlyLimit: 900 }, b]);
  });

  it('adds a budget it has not seen', () => {
    expect(withSavedBudget([a], b)).toEqual([a, b]);
  });
});

describe('replaceLimitNote', () => {
  it('says what saving will replace', () => {
    expect(replaceLimitNote('Food', 5000, (n) => '₹' + n.toLocaleString('en-IN')))
      .toBe('Food already has a ₹5,000 budget. Saving replaces that limit.');
  });
});

describe('limitProblem', () => {
  it.each([
    ['100.555', 'Monthly limit can have at most 2 decimal places.'],
    ['1,000.125', 'Monthly limit can have at most 2 decimal places.'],
    ['1000000000000', 'Monthly limit is too large.'],
    ['10,00,00,00,00,000', 'Monthly limit is too large.'],
  ])('explains %j, and parseLimit refuses it', (typed, message) => {
    expect(limitProblem(typed)).toBe(message);
    expect(parseLimit(typed)).toBeNull();
  });

  it.each(['12,50', '1,00', '1,000,00', '12,5', '1,2,3', ',500', '5,000,', '12,50.5'])(
    'asks about the commas in %j instead of guessing the amount', (typed) => {
      expect(limitProblem(typed)).toBe('Check the commas in this amount. For paise, use a point: 12.50.');
      expect(parseLimit(typed)).toBeNull();
    },
  );

  it.each(['2,60,000', '260,000', '1,000.50'])('has no complaint about the commas in %j', (typed) => {
    expect(limitProblem(typed)).toBeNull();
  });

  it('accepts the largest limit the server stores', () => {
    expect(parseLimit('999999999999.99')).toBe(999999999999.99);
    expect(limitProblem('999999999999.99')).toBeNull();
  });

  it.each(['', '0', 'abc', '1e99', '5000'])('has nothing specific to say about %j', (typed) => {
    expect(limitProblem(typed)).toBeNull();
  });
});
