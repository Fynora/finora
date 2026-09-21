import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { UsageSection } from './UsageSection';
import { accountsApi, analyticsApi, budgetsApi, goalsApi, usageApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  accountsApi: { list: jest.fn() },
  goalsApi: { list: jest.fn() },
  budgetsApi: { list: jest.fn() },
  analyticsApi: { importStatistics: jest.fn() },
  usageApi: { viewCount: jest.fn() },
}));

const accounts = accountsApi as jest.Mocked<typeof accountsApi>;
const goals = goalsApi as jest.Mocked<typeof goalsApi>;
const budgets = budgetsApi as jest.Mocked<typeof budgetsApi>;
const analytics = analyticsApi as jest.Mocked<typeof analyticsApi>;
const usage = usageApi as jest.Mocked<typeof usageApi>;

function renderSection(isFree = false, planName: string | null = 'Premium') {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}><UsageSection isFree={isFree} planName={planName} /></QueryClientProvider>
  );
}

function resolveAll() {
  accounts.list.mockResolvedValue([{ id: 'a1' }, { id: 'a2' }] as any);
  goals.list.mockResolvedValue([{ id: 'g1' }] as any);
  budgets.list.mockResolvedValue([] as any);
  analytics.importStatistics.mockResolvedValue({ totalStatements: 3, totalTransactionsImported: 12450 } as any);
  usage.viewCount.mockResolvedValue({ viewCount: 7 });
}

describe('UsageSection', () => {
  beforeEach(() => {
    [accounts.list, goals.list, budgets.list, analytics.importStatistics, usage.viewCount].forEach((m) => m.mockReset());
  });

  it('shows real counts for every tile, with Indian digit grouping and singular/plural labels', async () => {
    resolveAll();
    renderSection();

    expect(await screen.findByLabelText('Smart Insights: 7 insights viewed')).toBeTruthy();
    expect(screen.getByLabelText('Goals Created: 1 goal')).toBeTruthy();
    expect(screen.getByLabelText('Budgets Managed: 0 budgets')).toBeTruthy();
    expect(screen.getByLabelText('Statement Imports: 3 statements imported')).toBeTruthy();
    expect(screen.getByLabelText('Connected Accounts: 2 accounts')).toBeTruthy();
    expect(screen.getByLabelText('Transactions Imported: 12,450 transactions')).toBeTruthy();
  });

  it('titles the card by the real plan name, not "Premium" for every paid tier', async () => {
    resolveAll();
    const first = renderSection(true, 'Free');
    expect(await screen.findByText("How you're using Fynora")).toBeTruthy();
    first.unmount();
    const second = renderSection(false, 'Plus');
    expect(await screen.findByText("How you're using Plus")).toBeTruthy();
    second.unmount();
    renderSection(false, null);
    expect(await screen.findByText("How you're using Premium")).toBeTruthy();
  });

  it('shows a dash, never a false zero, for a tile whose query failed', async () => {
    resolveAll();
    analytics.importStatistics.mockRejectedValue(new Error('boom'));
    usage.viewCount.mockRejectedValue(new Error('boom'));
    renderSection();

    expect(await screen.findByLabelText('Connected Accounts: 2 accounts')).toBeTruthy();
    expect(screen.getByLabelText('Statement Imports: — statements imported')).toBeTruthy();
    expect(screen.getByLabelText('Transactions Imported: — transactions')).toBeTruthy();
    expect(screen.getByLabelText('Smart Insights: — insights viewed')).toBeTruthy();
  });
});
