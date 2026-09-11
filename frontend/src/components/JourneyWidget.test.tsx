import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JourneyWidget } from './JourneyWidget';
import { dashboardApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ dashboardApi: { timeline: vi.fn(), momentum: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
}

describe('JourneyWidget', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.momentum).mockResolvedValue({ activeMonths: 0, windowMonths: 6 });
  });

  it('shows the most recent Landmark event title', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([
      { eventType: 'GOAL_COMPLETED', bucket: 'TRANSFORMATION', importance: 'LANDMARK', permanent: true,
        title: 'Completed Emergency Fund', detail: null, occurredAt: '2026-03-01T00:00:00Z' },
      { eventType: 'FIRST_BUDGET_CREATED', bucket: 'STARTING', importance: 'MAJOR', permanent: true,
        title: 'Created your first budget', detail: null, occurredAt: '2026-01-01T00:00:00Z' },
    ]);

    renderWithClient(<JourneyWidget />);

    expect(await screen.findByText('Completed Emergency Fund')).toBeInTheDocument();
  });

  it('falls back to the most recent Major event when there is no Landmark event yet', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([
      { eventType: 'FIRST_BUDGET_CREATED', bucket: 'STARTING', importance: 'MAJOR', permanent: true,
        title: 'Created your first budget', detail: null, occurredAt: '2026-01-01T00:00:00Z' },
    ]);

    renderWithClient(<JourneyWidget />);

    expect(await screen.findByText('Created your first budget')).toBeInTheDocument();
  });

  it('renders nothing when the timeline is empty', () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([]);

    const { container } = renderWithClient(<JourneyWidget />);

    expect(container).toBeEmptyDOMElement();
  });

  it('shows the momentum line when there is active momentum', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([
      { eventType: 'GOAL_COMPLETED', bucket: 'TRANSFORMATION', importance: 'LANDMARK', permanent: true,
        title: 'Completed Emergency Fund', detail: null, occurredAt: '2026-03-01T00:00:00Z' },
    ]);
    vi.mocked(dashboardApi.momentum).mockResolvedValue({ activeMonths: 4, windowMonths: 6 });

    renderWithClient(<JourneyWidget />);

    expect(await screen.findByText('Active 4 of the last 6 months')).toBeInTheDocument();
  });
});
