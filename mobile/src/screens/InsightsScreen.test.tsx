import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { InsightsScreen } from './InsightsScreen';
import { categoriesApi, dashboardApi, insightsApi, onboardingApi, recurringApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  insightsApi: { get: jest.fn() },
  recurringApi: { list: jest.fn(), dismiss: jest.fn() },
  dashboardApi: { summary: jest.fn() },
  categoriesApi: { list: jest.fn() },
  // Getting-started checklist dwell timer (D-onboarding) -- default to "no VIEW_INSIGHTS item in
  // the response" so it never fires in tests that don't care about it.
  onboardingApi: {
    getChecklist: jest.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: jest.fn().mockResolvedValue(undefined),
  },
}));

const insights = insightsApi as jest.Mocked<typeof insightsApi>;
const recurring = recurringApi as jest.Mocked<typeof recurringApi>;
const dashboard = dashboardApi as jest.Mocked<typeof dashboardApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <InsightsScreen />
    </QueryClientProvider>
  );
}

describe('InsightsScreen', () => {
  beforeEach(() => {
    insights.get.mockReset().mockResolvedValue({
      sentences: ['You spent 18% less on dining this month.'],
      movers: [
        { category: 'Dining', current: 4000, priorAverage: 6000, pctChange: -33 },
        { category: 'Travel', current: 9000, priorAverage: 3000, pctChange: 200 },
        // Filtered out: no prior average means no comparison to make.
        { category: 'New thing', current: 500, priorAverage: 0, pctChange: null },
      ],
      coverageCaveat: null,
      biggestCategory: null,
      topMerchant: null,
    });
    recurring.list.mockReset().mockResolvedValue([
      {
        merchant: 'netflix', label: 'Monthly', averageAmount: 649, occurrences: 6,
        lastDate: '2026-07-04', nextEstimate: '2026-08-04',
      },
    ]);
    // Never resolves by default, so tests that don't care about the banner/glance card see them
    // stay absent -- NOT mockResolvedValue(undefined), which TanStack Query logs a "Query data
    // cannot be undefined" console.error for.
    dashboard.summary.mockReset().mockReturnValue(new Promise(() => {}));
    categories.list.mockReset().mockResolvedValue([]);
  });

  it('renders recurring payments, movers, and the full observations behind "See all insights"', async () => {
    renderScreen();

    expect(await screen.findByText('netflix')).toBeTruthy();
    expect(screen.getByText('Dining')).toBeTruthy();
    // Sentences (like this one) are collapsed by default -- only reachable via the toggle.
    expect(screen.queryByText('You spent 18% less on dining this month.')).toBeNull();

    fireEvent.press(screen.getByText('See all insights'));

    expect(screen.getByText('You spent 18% less on dining this month.')).toBeTruthy();
  });

  it('dismisses a recurring group and removes it from the list', async () => {
    recurring.dismiss.mockReset().mockResolvedValue(undefined);
    renderScreen();
    await screen.findByText('netflix');

    fireEvent.press(screen.getByTestId('dismiss-recurring-netflix'));

    await waitFor(() => expect(recurring.dismiss).toHaveBeenCalledWith('netflix'));
    await waitFor(() => expect(screen.queryByText('netflix')).toBeNull());
  });

  // Saying plainly that these are statistics, not an AI assistant, is the honest framing -- the
  // same numbers read as something else entirely without it.
  it('does not let the observations pass for AI output', async () => {
    renderScreen();

    expect(await screen.findByText(/not an\s+AI-generated assistant/)).toBeTruthy();
  });

  it('drops movers with nothing to compare against', async () => {
    renderScreen();
    await screen.findByText('Dining');

    expect(screen.queryByText('New thing')).toBeNull();
  });

  // Spending more is the bad direction here -- the inverse of the Dashboard's income KPI.
  it('marks a rise in spending as the adverse direction', async () => {
    renderScreen();

    expect(await screen.findByLabelText(/Travel spend was 200% more than your recent average/)).toBeTruthy();
    expect(screen.getByLabelText(/Dining spend was 33% lower than your recent average/)).toBeTruthy();
  });

  /**
   * useQueries rather than the web page's Promise.all: one rejected promise there loses BOTH
   * sections, though they come from unrelated endpoints.
   */
  it('keeps recurring payments when the insights endpoint fails', async () => {
    insights.get.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText('netflix')).toBeTruthy();
    expect(screen.getByText(/Couldn't load your insights/)).toBeTruthy();
  });

  it('keeps insights when the recurring endpoint fails', async () => {
    recurring.list.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText('Dining')).toBeTruthy();
    expect(screen.getByText(/Couldn't load recurring payments/)).toBeTruthy();
  });

  it('explains why a section is empty rather than showing a blank card', async () => {
    insights.get.mockReset().mockResolvedValue({
      sentences: [], movers: [], coverageCaveat: null, biggestCategory: null, topMerchant: null,
    });
    recurring.list.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/at least 2 charges from the same merchant/)).toBeTruthy();
    expect(screen.getByText(/Nothing stands out this month yet/)).toBeTruthy();
  });

  it('shows the static notice and skeleton sections immediately, before either query resolves', () => {
    insights.get.mockReset().mockReturnValue(new Promise(() => {}));
    recurring.list.mockReset().mockReturnValue(new Promise(() => {}));

    renderScreen();

    expect(screen.getByText(/not an\s+AI-generated assistant/)).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
    expect(screen.queryByText('Key Insights')).toBeNull();
  });

  // Each card gates on only the query its own data comes from -- a slow recurringApi.list() must
  // not hold Key Insights (which reads insightsQ only) on its skeleton too.
  it('reveals Key Insights independently of a still-loading Recurring Payments', async () => {
    recurring.list.mockReset().mockReturnValue(new Promise(() => {}));

    renderScreen();

    expect(await screen.findByText('Dining')).toBeTruthy();
    expect(screen.queryByText('netflix')).toBeNull();
    // Only Recurring Payments' own shimmer is left -- Key Insights already has real data.
    expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
  });

  it('reveals Recurring Payments independently of a still-loading insights query', async () => {
    insights.get.mockReset().mockReturnValue(new Promise(() => {}));

    renderScreen();

    expect(await screen.findByText('netflix')).toBeTruthy();
    expect(screen.queryByText('Key Insights')).toBeNull();
  });

  it('shows the banner and This Month at a Glance once dashboard-summary resolves', async () => {
    dashboard.summary.mockResolvedValue({
      monthlyIncome: 145000, monthlyExpense: 12831, incomeDeltaPct: 12, expenseDeltaPct: -22,
      netCashFlow: 132169, netDeltaPct: 28, spendByCategory: { Shopping: 5798, 'Food & Dining': 900 },
    } as any);
    renderScreen();

    expect(await screen.findByText("You're on track!")).toBeTruthy();
    expect(screen.getByText(/22% lower than last month/)).toBeTruthy();
    expect(screen.getByText('This Month at a Glance')).toBeTruthy();
    expect(screen.getByText('2')).toBeTruthy(); // Categories count
  });

  it('frames the banner as a heads-up when spending is up, not "on track"', async () => {
    dashboard.summary.mockResolvedValue({
      monthlyIncome: 100000, monthlyExpense: 40000, incomeDeltaPct: 0, expenseDeltaPct: 15,
      netCashFlow: 60000, netDeltaPct: -5, spendByCategory: { Shopping: 40000 },
    } as any);
    renderScreen();

    expect(await screen.findByText('Heads up')).toBeTruthy();
    expect(screen.getByText(/15% higher than last month/)).toBeTruthy();
  });

  it('renders the Spending by Category donut and drills through on a slice tap', async () => {
    dashboard.summary.mockResolvedValue({
      monthlyIncome: 1, monthlyExpense: 10000, incomeDeltaPct: 0, expenseDeltaPct: 0, netCashFlow: 0,
      netDeltaPct: 0, spendByCategory: { Dining: 4000, Groceries: 6000 },
      reportingMonth: '2026-08', reportingMonthIsCurrent: false,
    } as any);
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();
    renderScreen();

    expect(await screen.findByText('Spending by Category')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Dining: ₹4,000' }));

    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        categoryName: 'Dining', dateFrom: '2026-08-01', dateTo: '2026-08-31', label: 'Dining · Aug 26',
      }),
    });
  });

  it('renders neither the banner nor the glance card while summary is still loading', async () => {
    renderScreen(); // dashboard.summary defaults to a never-resolving promise
    await screen.findByText(/not an\s+AI-generated assistant/);
    expect(screen.queryByText('This Month at a Glance')).toBeNull();
  });

  it('renders the biggest-category and top-merchant Key Insights rows and opens Transactions on tap', async () => {
    insights.get.mockReset().mockResolvedValue({
      sentences: [], movers: [], coverageCaveat: null,
      biggestCategory: { name: 'Shopping', amount: 5798 },
      topMerchant: { name: 'myntra', amount: 3299 },
    });
    categories.list.mockResolvedValue([
      { id: 'c1', name: 'Shopping', isSystem: true, icon: 'shopping-bag', color: 'blue' },
    ] as never);
    renderScreen();

    expect(await screen.findByLabelText(/Biggest category: Shopping at ₹5,798/)).toBeTruthy();
    expect(screen.getByLabelText(/Top merchant: myntra at ₹3,299/)).toBeTruthy();

    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();
    fireEvent.press(screen.getByLabelText(/Top merchant: myntra/));

    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: { keyword: 'myntra', label: 'myntra', nonce: expect.any(Number) },
    });
  });

  it('opens Settings from the header gear', async () => {
    renderScreen();
    await screen.findByText(/not an\s+AI-generated assistant/);
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    fireEvent.press(screen.getByLabelText('Settings'));

    expect(navigate).toHaveBeenCalledWith('Settings');
  });

  describe('drill-through into the ledger (Track C/C4)', () => {
    it('opens Transactions filtered to just this category -- no date range, since none is known here', async () => {
      renderScreen();
      await screen.findByText('Dining');
      const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
      navigate.mockClear();

      fireEvent.press(screen.getByLabelText(/Dining spend was 33% lower than your recent average/));

      expect(navigate).toHaveBeenCalledWith('Transactions', {
        filters: { categoryName: 'Dining', label: 'Dining', nonce: expect.any(Number) },
      });
    });
  });
});

// D3 (Track D security cleanup). Spend movers name real merchants/amounts -- as screenshot-
// attractive as anything on the Dashboard or Accounts screen, which already guard against this.
describe('screen capture protection (Track D/D3)', () => {
  it('calls usePreventScreenCapture on mount', () => {
    renderScreen();

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });
});

describe('getting-started checklist dwell timer', () => {
  beforeEach(() => {
    insights.get.mockReset().mockResolvedValue({
      sentences: [], movers: [], coverageCaveat: null, biggestCategory: null, topMerchant: null,
    });
    recurring.list.mockReset().mockResolvedValue([]);
  });

  it('marks VIEW_INSIGHTS complete after a 1.5s dwell', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'VIEW_INSIGHTS', completed: false }], completedCount: 0, totalCount: 6,
    });

    renderScreen();

    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(1500); });

    expect(onboardingApi.completeChecklistItem).toHaveBeenCalledWith('VIEW_INSIGHTS');
    jest.useRealTimers();
  });

  it('does not fire if the item is already complete', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'VIEW_INSIGHTS', completed: true }], completedCount: 1, totalCount: 6,
    });

    renderScreen();

    await act(async () => { await jest.advanceTimersByTimeAsync(0); });
    await act(async () => { await jest.advanceTimersByTimeAsync(1500); });

    expect(onboardingApi.completeChecklistItem).not.toHaveBeenCalled();
    jest.useRealTimers();
  });
});
