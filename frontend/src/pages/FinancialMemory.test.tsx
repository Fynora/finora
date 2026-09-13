import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import FinancialMemory from './FinancialMemory';
import { workspaceApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ workspaceApi: { dashboard: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('FinancialMemory', () => {
  it('shows real counts once the workspace summary loads', async () => {
    vi.mocked(workspaceApi.dashboard).mockResolvedValue({
      totalTransactions: 1284,
      totalAccounts: 3,
      totalMerchants: 42,
      learnedMerchants: 30,
      activeRules: 5,
      statementsImported: 18,
      monthsOfHistory: 14,
      completenessPercent: 91,
    });

    renderWithClient(<FinancialMemory />);

    expect(await screen.findByText('91%')).toBeInTheDocument();
    expect(await screen.findByText('1y 2m')).toBeInTheDocument();
    expect(await screen.findByText('3')).toBeInTheDocument();
    expect(await screen.findByText('1,284')).toBeInTheDocument();
  });

  // Bug fix: a failed fetch used to fall through to the metric grid with `data` undefined,
  // rendering every card as a false "0" -- indistinguishable from a genuinely brand-new account.
  it('shows an error message instead of false zeros when the fetch fails', async () => {
    vi.mocked(workspaceApi.dashboard).mockRejectedValue(new Error('network error'));

    renderWithClient(<FinancialMemory />);

    expect(await screen.findByText(/Couldn't load your financial memory/)).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });
});
