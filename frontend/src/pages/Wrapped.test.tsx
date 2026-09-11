import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import Wrapped from './Wrapped';
import { dashboardApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ dashboardApi: { wrapped: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('Wrapped', () => {
  it('shows the year, landmark titles, and contribution count', async () => {
    vi.mocked(dashboardApi.wrapped).mockResolvedValue({
      year: 2026, landmarksReached: 2, goalContributions: 14,
      landmarkTitles: ['Completed Emergency Fund', 'Saved your first ₹10,000'],
    });

    renderWithClient(<Wrapped />);

    expect(await screen.findByText('2026')).toBeInTheDocument();
    expect(await screen.findByText('Completed Emergency Fund')).toBeInTheDocument();
    expect(await screen.findByText('14')).toBeInTheDocument();
  });
});
