import { act, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { FinancialMemoryScreen } from './FinancialMemoryScreen';
import { recurringApi, workspaceApi } from '../api/endpoints';
import { usePreventScreenCapture } from '../lib/screenCapture';

jest.mock('../api/endpoints', () => ({
  workspaceApi: { dashboard: jest.fn() },
  recurringApi: { list: jest.fn() },
}));

const workspace = workspaceApi as jest.Mocked<typeof workspaceApi>;
const recurring = recurringApi as jest.Mocked<typeof recurringApi>;

function summary(overrides: Record<string, unknown> = {}) {
  return {
    totalTransactions: 12450, totalAccounts: 3, totalMerchants: 88, learnedMerchants: 40,
    identifiedMerchants: 52, activeRules: 6,
    statementsImported: 9, monthsOfHistory: 14, completenessPercent: 92, totalManualCorrections: 5, ...overrides,
  } as any;
}

function renderScreen() {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={queryClient}><FinancialMemoryScreen /></QueryClientProvider>);
}

describe('FinancialMemoryScreen', () => {
  beforeEach(() => {
    workspace.dashboard.mockReset();
    recurring.list.mockReset().mockResolvedValue([]);
  });

  it('reports each figure, formatting history as years and months and counts in Indian grouping', async () => {
    workspace.dashboard.mockResolvedValue(summary());
    renderScreen();

    expect(await screen.findByLabelText('History: 1y 2m, since your first statement')).toBeTruthy();
    expect(screen.getByLabelText('Completeness: 92%, months covered, no gaps')).toBeTruthy();
    expect(screen.getByLabelText('Accounts connected: 3')).toBeTruthy();
    expect(screen.getByLabelText('Transactions processed: 12,450')).toBeTruthy();
    // 52 identified from the user's own activity -- not the 88 merchant rows (starter brands included).
    expect(screen.getByLabelText('Merchants identified: 52, 40 learned')).toBeTruthy();
    expect(screen.getByLabelText('Rules learned: 6')).toBeTruthy();
    expect(screen.getByLabelText("Manual corrections: 5, auto-categorized imports you've corrected")).toBeTruthy();
  });

  it('reports no merchants identified for a fresh account, though 34 starter brands exist', async () => {
    workspace.dashboard.mockResolvedValue(summary({
      totalTransactions: 0, totalAccounts: 0, totalMerchants: 34, learnedMerchants: 0, identifiedMerchants: 0,
      monthsOfHistory: null, completenessPercent: null, totalManualCorrections: 0,
    }));
    renderScreen();

    expect(await screen.findByLabelText('Merchants identified: 0, 0 learned')).toBeTruthy();
    expect(screen.queryByLabelText(/Merchants identified: 34/)).toBeNull();
  });

  it.each([
    [null, '—'], [1, '1 month'], [11, '11 months'], [12, '1 year'], [24, '2 years'], [13, '1y 1m'],
  ])('formats %s months of history as %s', async (months, expected) => {
    workspace.dashboard.mockResolvedValue(summary({ monthsOfHistory: months }));
    renderScreen();

    expect(await screen.findByLabelText(new RegExp(`^History: ${expected}`))).toBeTruthy();
  });

  it('shows a dash for completeness when there is nothing to measure yet, not 0%', async () => {
    workspace.dashboard.mockResolvedValue(summary({ completenessPercent: null, monthsOfHistory: null }));
    renderScreen();

    expect(await screen.findByLabelText(/^Completeness: —/)).toBeTruthy();
  });

  it('shows a load failure instead of a grid of false zeros', async () => {
    workspace.dashboard.mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load your financial memory/)).toBeTruthy();
    expect(screen.queryByLabelText(/^Accounts connected/)).toBeNull();
  });

  it('lists recognized recurring payments with their cadence and average amount', async () => {
    workspace.dashboard.mockResolvedValue(summary());
    recurring.list.mockResolvedValue([
      { merchant: 'netflix', label: 'Monthly', averageAmount: 649, occurrences: 6, lastDate: '2026-07-04', nextEstimate: '2026-08-04' },
    ] as any);
    renderScreen();

    expect(await screen.findByText('netflix')).toBeTruthy();
    expect(screen.getByText('Monthly')).toBeTruthy();
    expect(screen.getByText('₹649')).toBeTruthy();
  });

  it('blocks screenshots and screen recording, since it names real merchants and amounts', async () => {
    workspace.dashboard.mockResolvedValue(summary());
    renderScreen();
    await screen.findByLabelText('Accounts connected: 3');

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });

  // Offline, React Query pauses a cold query: not loading, not an error, no data. Reading that as
  // "nothing recognized" would tell someone with real subscriptions that Fynora found none.
  it('does not claim nothing is recognized while offline with nothing loaded', async () => {
    onlineManager.setOnline(false);
    try {
      workspace.dashboard.mockResolvedValue(summary());
      renderScreen();
      await act(async () => {});

      expect(screen.queryByText(/No recurring payments recognized yet/)).toBeNull();
      expect(screen.getByText(/Couldn't load your recurring payments/)).toBeTruthy();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('explains an empty recurring list', async () => {
    workspace.dashboard.mockResolvedValue(summary());
    renderScreen();

    expect(await screen.findByText(/No recurring payments recognized yet/)).toBeTruthy();
  });

  it('keeps the metrics when the recurring list fails, and says so', async () => {
    workspace.dashboard.mockResolvedValue(summary());
    recurring.list.mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByLabelText('Accounts connected: 3')).toBeTruthy();
    expect(await screen.findByText(/Couldn't load your recurring payments/)).toBeTruthy();
  });
});
