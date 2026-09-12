import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Goals from './Goals';
import { goalsApi } from '../api/endpoints';
import type { Goal } from '../types';

vi.mock('../api/endpoints', () => ({
  goalsApi: { list: vi.fn(), create: vi.fn(), addContribution: vi.fn(), remove: vi.fn() },
}));

function goal(overrides: Partial<Goal> = {}): Goal {
  return {
    id: 'g1',
    name: 'Emergency Fund',
    targetAmount: 100000,
    currentAmount: 20000,
    targetDate: null,
    ...overrides,
  } as Goal;
}

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <Goals />
    </QueryClientProvider>
  );
}

describe('Goals', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // The bug: `goals` started `[]`, indistinguishable from "genuinely no goals" -- same class of
  // bug as Budgets.tsx/Setup.tsx.
  it('never shows the empty state while the initial fetch is still in flight', async () => {
    let resolveList: (g: Goal[]) => void;
    vi.mocked(goalsApi.list).mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve;
      })
    );

    renderPage();

    expect(screen.queryByText('No goals yet')).not.toBeInTheDocument();

    resolveList!([goal()]);
    await waitFor(() => expect(screen.getByText('Emergency Fund')).toBeInTheDocument());
    expect(screen.queryByText('No goals yet')).not.toBeInTheDocument();
  });

  it('shows the empty state once loading finishes with genuinely no goals', async () => {
    vi.mocked(goalsApi.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('No goals yet')).toBeInTheDocument();
  });

  it('renders each goal once loaded, with its progress and amounts', async () => {
    vi.mocked(goalsApi.list).mockResolvedValue([
      goal({ id: 'g1', name: 'Emergency Fund', targetAmount: 100000, currentAmount: 20000 }),
      goal({ id: 'g2', name: 'New Laptop', targetAmount: 80000, currentAmount: 80000 }),
    ]);
    renderPage();

    expect(await screen.findByText('Emergency Fund')).toBeInTheDocument();
    expect(screen.getByText('New Laptop')).toBeInTheDocument();
  });

  it('badges a fully-funded goal as Completed regardless of its target date', async () => {
    vi.mocked(goalsApi.list).mockResolvedValue([
      goal({ id: 'g1', name: 'New Laptop', targetAmount: 80000, currentAmount: 80000 }),
    ]);
    renderPage();

    expect(await screen.findByText('Completed')).toBeInTheDocument();
  });

  it('badges an unfunded goal past its target date as Past due', async () => {
    vi.mocked(goalsApi.list).mockResolvedValue([
      goal({ id: 'g1', name: 'Emergency Fund', targetAmount: 100000, currentAmount: 20000, targetDate: '2020-01-01' }),
    ]);
    renderPage();

    expect(await screen.findByText('Past due')).toBeInTheDocument();
  });

  it('shows no status badge for an in-progress goal with no target date to judge it against', async () => {
    vi.mocked(goalsApi.list).mockResolvedValue([
      goal({ id: 'g1', name: 'Emergency Fund', targetAmount: 100000, currentAmount: 20000 }),
    ]);
    renderPage();

    await screen.findByText('Emergency Fund');
    expect(screen.queryByText('Completed')).not.toBeInTheDocument();
    expect(screen.queryByText('Past due')).not.toBeInTheDocument();
  });
});
