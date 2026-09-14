import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { ChecklistWidget } from './ChecklistWidget';
import { onboardingApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ onboardingApi: { getChecklist: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

const SIX_ITEMS = [
  { key: 'COMPLETE_PROFILE', completed: true },
  { key: 'IMPORT_STATEMENT', completed: false },
  { key: 'REVIEW_TRANSACTIONS', completed: false },
  { key: 'CREATE_BUDGET', completed: false },
  { key: 'CREATE_GOAL', completed: false },
  { key: 'VIEW_INSIGHTS', completed: false },
];

describe('ChecklistWidget', () => {
  it('shows progress text for a partially complete checklist', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: SIX_ITEMS, completedCount: 1, totalCount: 6,
    });
    renderWithClient(<ChecklistWidget />);
    await waitFor(() => expect(screen.getByText('1 of 6 complete')).toBeInTheDocument());
  });

  it('renders nothing once completedCount equals totalCount', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [], completedCount: 6, totalCount: 6,
    });
    const { container } = renderWithClient(<ChecklistWidget />);
    await waitFor(() => expect(container.textContent).toBe(''));
  });

  it('shows every item label', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: SIX_ITEMS, completedCount: 1, totalCount: 6,
    });
    renderWithClient(<ChecklistWidget />);
    await waitFor(() => expect(screen.getByText(/Complete your profile/)).toBeInTheDocument());
    expect(screen.getByText(/Import first statement/)).toBeInTheDocument();
  });

  it('marks a completed item with a checkmark, distinct from an incomplete one', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: SIX_ITEMS, completedCount: 1, totalCount: 6,
    });
    renderWithClient(<ChecklistWidget />);

    const completedLabel = await screen.findByText('Complete your profile');
    const completedCircle = completedLabel.closest('li')!.querySelector('span.rounded-full');
    expect(completedCircle).toHaveClass('bg-primary');
    expect(completedCircle?.querySelector('svg')).toBeTruthy(); // the Check icon

    const incompleteLabel = screen.getByText('Import first statement');
    const incompleteCircle = incompleteLabel.closest('li')!.querySelector('span.rounded-full');
    expect(incompleteCircle).toHaveClass('border-border');
    expect(incompleteCircle?.querySelector('svg')).toBeFalsy();
  });

  // Bug fix: this widget rendered nothing at all while its own query was in flight -- not even a
  // skeleton -- so it just silently popped into existence whenever the fetch took long enough to
  // notice, shifting everything below it down. Every other independently-loaded Dashboard section
  // already reserves its space with a skeleton (see useDelayedLoading's own 200ms showAfter,
  // tested in isolation in that hook's own test file -- this only proves ChecklistWidget wires
  // isLoading up to it, not the timing itself). Same pattern as Ledger.test.tsx's own
  // fake-timer skeleton tests.
  it('shows a loading skeleton once the fetch has been in flight long enough to notice', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    // Never resolves during this test -- isLoading stays true for its whole duration.
    vi.mocked(onboardingApi.getChecklist).mockReturnValue(new Promise(() => {}));

    renderWithClient(<ChecklistWidget />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(250);
    });

    expect(screen.getByText('Loading your getting-started checklist')).toBeInTheDocument();
    vi.useRealTimers();
  });

  it('shows nothing during the brief window before a fast-resolving fetch settles', () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(onboardingApi.getChecklist).mockReturnValue(new Promise(() => {}));

    const { container } = renderWithClient(<ChecklistWidget />);

    expect(container.textContent).toBe('');
    vi.useRealTimers();
  });

  it('starts expanded, and collapses/re-expands on toggle', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: SIX_ITEMS, completedCount: 1, totalCount: 6,
    });
    const user = userEvent.setup();
    renderWithClient(<ChecklistWidget />);

    await screen.findByText('Complete your profile');
    const toggle = screen.getByRole('button', { name: /getting started/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Complete your profile')).not.toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByText('Complete your profile')).toBeInTheDocument();
  });
});
