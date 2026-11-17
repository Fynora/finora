import fs from 'fs';
import path from 'path';
import {
  budgetPace,
  describePace,
  groupBudgets,
  monthClock,
  overallPace,
  paceA11yLabel,
  GROUP_LABEL,
} from './budgetPace';

// Same cases as frontend/src/lib/budgetPace.test.ts, run under the phone app's Jest and JS engine,
// plus the parity check at the bottom.

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

describe('parity with the web app', () => {
  it('is byte-identical to frontend/src/lib/budgetPace.ts, so both apps say the same thing', () => {
    const web = fs.readFileSync(path.join(__dirname, '../../../frontend/src/lib/budgetPace.ts'), 'utf8');
    expect(fs.readFileSync(path.join(__dirname, 'budgetPace.ts'), 'utf8')).toBe(web);
  });
});
