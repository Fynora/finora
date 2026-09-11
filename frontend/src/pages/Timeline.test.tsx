import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import Timeline from './Timeline';
import { dashboardApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ dashboardApi: { timeline: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('Timeline', () => {
  it('groups events by year, newest year first', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([
      { eventType: 'GOAL_COMPLETED', bucket: 'TRANSFORMATION', importance: 'LANDMARK', permanent: true,
        title: 'Completed Emergency Fund', detail: null, occurredAt: '2027-02-01T00:00:00Z' },
      { eventType: 'FIRST_BUDGET_CREATED', bucket: 'STARTING', importance: 'MAJOR', permanent: true,
        title: 'Created your first budget', detail: null, occurredAt: '2026-01-01T00:00:00Z' },
    ]);

    renderWithClient(<Timeline />);

    const headings = await screen.findAllByRole('heading', { level: 3 });
    expect(headings.map((h) => h.textContent)).toEqual(['2027', '2026']);
  });

  it('shows an empty state when there are no events yet', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([]);

    renderWithClient(<Timeline />);

    expect(await screen.findByText(/your journey starts here/i)).toBeInTheDocument();
  });
});
