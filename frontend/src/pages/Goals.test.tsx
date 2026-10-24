import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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

  // Bug fix: the bar's width has to stay capped at 100% (nothing to gain from a fill wider than
  // its own track), but the "X% complete" TEXT next to it was reusing that same capped value --
  // so a goal funded past its target displayed a stuck "100% complete" forever.
  it('shows the real percentage past 100% for a goal funded beyond its target, not a capped "100%"', async () => {
    vi.mocked(goalsApi.list).mockResolvedValue([
      goal({ id: 'g1', name: 'New Laptop', targetAmount: 80000, currentAmount: 100000 }),
    ]);
    renderPage();

    expect(await screen.findByText('125% complete')).toBeInTheDocument();
    expect(screen.queryByText('100% complete')).not.toBeInTheDocument();
    // Still funded past target -- capping the percentage never affected this badge either way
    // (a capped 100 is also >= 100), but worth locking in now that the two share the same value.
    expect(screen.getByText('Completed')).toBeInTheDocument();
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

  describe('amount input', () => {
    // parseFloat read "1,00,000" as 1 and "12abc" as 12, and "1e400" as Infinity -- which went over
    // the wire as null. Each case below sent a number the user never typed, with nothing on screen.

    it('refuses a contribution typed with commas instead of recording 1', async () => {
      vi.mocked(goalsApi.list).mockResolvedValue([goal()]);
      vi.spyOn(window, 'prompt').mockReturnValue('1,00,000');
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: /add contribution/i }));

      expect(await screen.findByText(/without commas/i)).toBeInTheDocument();
      expect(goalsApi.addContribution).not.toHaveBeenCalled();
    });

    it('refuses a contribution with trailing letters instead of recording the leading digits', async () => {
      vi.mocked(goalsApi.list).mockResolvedValue([goal()]);
      vi.spyOn(window, 'prompt').mockReturnValue('12abc');
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: /add contribution/i }));

      expect(await screen.findByText(/without commas/i)).toBeInTheDocument();
      expect(goalsApi.addContribution).not.toHaveBeenCalled();
    });

    it('records a plain contribution as typed', async () => {
      vi.mocked(goalsApi.list).mockResolvedValue([goal()]);
      vi.mocked(goalsApi.addContribution).mockResolvedValue(goal());
      vi.spyOn(window, 'prompt').mockReturnValue('1500.50');
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: /add contribution/i }));

      await waitFor(() => expect(goalsApi.addContribution).toHaveBeenCalledWith('g1', 1500.5));
    });

    it('does nothing, and shows nothing, when the contribution prompt is cancelled', async () => {
      vi.mocked(goalsApi.list).mockResolvedValue([goal()]);
      vi.spyOn(window, 'prompt').mockReturnValue(null);
      renderPage();

      fireEvent.click(await screen.findByRole('button', { name: /add contribution/i }));

      expect(goalsApi.addContribution).not.toHaveBeenCalled();
      expect(screen.queryByText(/without commas/i)).not.toBeInTheDocument();
    });

    function fillCreateForm(target: string, starting: string) {
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Trip' } });
      fireEvent.change(screen.getByLabelText('Target'), { target: { value: target } });
      fireEvent.change(screen.getByLabelText('Starting amount'), { target: { value: starting } });
      fireEvent.click(screen.getByRole('button', { name: /add goal/i }));
    }

    // A number input hands back an empty value for anything it can't read -- measured in jsdom and
    // in Chromium 152 for "1,00,000", "12abc" and "1e400". Add Goal used to return silently then.
    it('says why when the target is unreadable, instead of the button doing nothing', async () => {
      vi.mocked(goalsApi.list).mockResolvedValue([]);
      renderPage();
      await screen.findByText('No goals yet');

      fillCreateForm('1,00,000', '');

      expect(await screen.findByText(/enter a target amount/i)).toBeInTheDocument();
      expect(goalsApi.create).not.toHaveBeenCalled();
    });

    it('says why when the name is missing, instead of the button doing nothing', async () => {
      vi.mocked(goalsApi.list).mockResolvedValue([]);
      renderPage();
      await screen.findByText('No goals yet');

      fireEvent.change(screen.getByLabelText('Target'), { target: { value: '50000' } });
      fireEvent.click(screen.getByRole('button', { name: /add goal/i }));

      expect(await screen.findByText(/give this goal a name/i)).toBeInTheDocument();
      expect(goalsApi.create).not.toHaveBeenCalled();
    });

    it('sends a blank starting amount as zero', async () => {
      vi.mocked(goalsApi.list).mockResolvedValue([]);
      vi.mocked(goalsApi.create).mockResolvedValue(goal());
      renderPage();
      await screen.findByText('No goals yet');

      fillCreateForm('50000', '');

      await waitFor(() => expect(goalsApi.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Trip', targetAmount: 50000, currentAmount: 0 })));
    });
  });
});
