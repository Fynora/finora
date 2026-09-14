import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import FinancialMemory from './FinancialMemory';
import { workspaceApi, recurringApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  workspaceApi: { dashboard: vi.fn() },
  recurringApi: { list: vi.fn() },
}));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

const baseSummary = {
  totalTransactions: 1284,
  totalAccounts: 3,
  totalMerchants: 42,
  learnedMerchants: 30,
  activeRules: 5,
  statementsImported: 18,
  monthsOfHistory: 14,
  completenessPercent: 91,
  totalManualCorrections: 27,
};

describe('FinancialMemory', () => {
  it('shows real counts once the workspace summary loads', async () => {
    vi.mocked(workspaceApi.dashboard).mockResolvedValue(baseSummary);
    vi.mocked(recurringApi.list).mockResolvedValue([]);

    renderWithClient(<FinancialMemory />);

    expect(await screen.findByText('91%')).toBeInTheDocument();
    expect(await screen.findByText('1y 2m')).toBeInTheDocument();
    expect(await screen.findByText('3')).toBeInTheDocument();
    expect(await screen.findByText('1,284')).toBeInTheDocument();
    expect(await screen.findByText('27')).toBeInTheDocument();
  });

  // Bug fix: a failed fetch used to fall through to the metric grid with `data` undefined,
  // rendering every card as a false "0" -- indistinguishable from a genuinely brand-new account.
  it('shows an error message instead of false zeros when the fetch fails', async () => {
    vi.mocked(workspaceApi.dashboard).mockRejectedValue(new Error('network error'));
    vi.mocked(recurringApi.list).mockResolvedValue([]);

    renderWithClient(<FinancialMemory />);

    expect(await screen.findByText(/Couldn't load your financial memory/)).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  // Issue #1452.
  it('lists recognized recurring payments once they load', async () => {
    vi.mocked(workspaceApi.dashboard).mockResolvedValue(baseSummary);
    vi.mocked(recurringApi.list).mockResolvedValue([
      { merchant: 'Netflix', label: 'Monthly', averageAmount: 649, occurrences: 6, lastDate: '2026-08-01', nextEstimate: '2026-09-01' },
    ]);

    renderWithClient(<FinancialMemory />);

    expect(await screen.findByText('Netflix')).toBeInTheDocument();
  });

  it('shows an empty-state message when nothing recurring has been recognized yet', async () => {
    vi.mocked(workspaceApi.dashboard).mockResolvedValue(baseSummary);
    vi.mocked(recurringApi.list).mockResolvedValue([]);

    renderWithClient(<FinancialMemory />);

    expect(await screen.findByText(/No recurring payments recognized yet/)).toBeInTheDocument();
  });

  // The recurring list and the main summary are independent queries -- one failing must not take
  // the other down, same principle Insights.tsx's insightsFailed/recurringError split already
  // follows for the identical pair of endpoints on that page.
  it('still shows the metric grid when only the recurring-payments fetch fails', async () => {
    vi.mocked(workspaceApi.dashboard).mockResolvedValue(baseSummary);
    vi.mocked(recurringApi.list).mockRejectedValue(new Error('network error'));

    renderWithClient(<FinancialMemory />);

    expect(await screen.findByText('91%')).toBeInTheDocument();
    expect(await screen.findByText(/Couldn't load your recurring payments/)).toBeInTheDocument();
  });
});
