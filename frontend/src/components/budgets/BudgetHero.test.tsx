import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BudgetHero } from './BudgetHero';
import { monthClock, overallPace } from '../../lib/budgetPace';

const money = (n: number) => (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');

const SAMPLE = [
  { spentThisMonth: 8400, monthlyLimit: 6000 },
  { spentThisMonth: 7200, monthlyLimit: 9000 },
  { spentThisMonth: 4500, monthlyLimit: 8000 },
  { spentThisMonth: 2000, monthlyLimit: 4000 },
  { spentThisMonth: 600, monthlyLimit: 2000 },
];

function renderHero(budgets: { spentThisMonth: number; monthlyLimit: number }[], today: Date) {
  const clock = monthClock(today);
  const totalSpent = budgets.reduce((s, b) => s + b.spentThisMonth, 0);
  const totalLimit = budgets.reduce((s, b) => s + b.monthlyLimit, 0);
  return render(
    <BudgetHero totalSpent={totalSpent} totalLimit={totalLimit} overall={overallPace(budgets, clock)} clock={clock} money={money} />,
  );
}

describe('BudgetHero', () => {
  it('shows spent, safe per day and the month-end forecast for the month so far', () => {
    renderHero(SAMPLE, new Date(2026, 10, 17));
    expect(screen.getByText('78%')).toBeInTheDocument();
    expect(screen.getByText('Spent of ₹29,000')).toBeInTheDocument();
    expect(screen.getByText('₹22,700')).toBeInTheDocument();
    expect(screen.getByText('57% of the month gone, 78% of budget used')).toBeInTheDocument();
    expect(screen.getByText('₹484')).toBeInTheDocument();
    expect(screen.getByText('13 days left · across all budgets')).toBeInTheDocument();
    expect(screen.getByText('At this pace by 30 Nov')).toBeInTheDocument();
    expect(screen.getByText('₹40,059')).toBeInTheDocument();
    expect(screen.getByText('₹11,059 over your budgets')).toBeInTheDocument();
  });

  it('says how far under the budgets the forecast lands when it is under', () => {
    renderHero([{ spentThisMonth: 10000, monthlyLimit: 30000 }], new Date(2026, 10, 17));
    expect(screen.getByText('₹17,647')).toBeInTheDocument();
    expect(screen.getByText('₹12,353 under your budgets')).toBeInTheDocument();
  });

  it('reads Last day on the final day of the month instead of a per-day amount', () => {
    renderHero([{ spentThisMonth: 1000, monthlyLimit: 2800 }], new Date(2026, 10, 30));
    expect(screen.getByText('Last day · ₹1,800 left')).toBeInTheDocument();
    expect(screen.queryByText('/ day')).not.toBeInTheDocument();
  });

  it('draws no spent arc when nothing is spent yet (a round cap would show a stray dot)', () => {
    renderHero([{ spentThisMonth: 0, monthlyLimit: 3000 }], new Date(2026, 10, 17));
    expect(screen.queryByTestId('ring-spent')).not.toBeInTheDocument();
    expect(screen.getByTestId('ring-month')).toBeInTheDocument();
  });

  it('gives screen readers one summary of the three figures', () => {
    renderHero(SAMPLE, new Date(2026, 10, 17));
    expect(screen.getByRole('group', {
      name: '₹22,700 spent of ₹29,000. ₹484 a day safe for 13 days. At this pace ₹40,059 by 30 Nov.',
    })).toBeInTheDocument();
  });
});
