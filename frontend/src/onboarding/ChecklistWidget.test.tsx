import { render, screen, waitFor } from '@testing-library/react';
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
