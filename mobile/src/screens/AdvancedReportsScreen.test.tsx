import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { RefreshControl } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AdvancedReportsScreen } from './AdvancedReportsScreen';
import {
  analyticsApi, entitlementsApi, reportsApi, type AdvancedReport, type EntitlementsDto,
} from '../api/endpoints';
import { fixtureMonth, fixtureReport } from '../lib/advancedReports/testFixtures';
import { shareAdvancedCsv } from '../lib/advancedReports/shareAdvancedCsv';
import { ThemeProvider } from '../theme';

// Premium is hidden in the app (lib/premiumVisibility.ts). These tests default it to visible so the
// Premium paths that still exist stay tested; individual tests turn it off.
const mockPremium = { visible: true };
jest.mock('../lib/premiumVisibility', () => ({
  get PREMIUM_PLAN_VISIBLE() {
    return mockPremium.visible;
  },
}));

jest.mock('../api/endpoints', () => ({
  entitlementsApi: { mine: jest.fn() },
  reportsApi: { availableMonths: jest.fn() },
  analyticsApi: { advanced: jest.fn(), categoryConfidence: jest.fn(), learningGrowth: jest.fn() },
}));

// The CSV builder is tested directly in shareAdvancedCsv.test.ts; what matters here is that the screen
// reaches it with the report actually on display, and survives a refusal.
jest.mock('../lib/advancedReports/shareAdvancedCsv', () => ({ shareAdvancedCsv: jest.fn() }));

/** A substring match: RN's toHaveTextContent compares the whole text when given a string. */
const text = (s: string) => new RegExp(s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));

const entitlements = entitlementsApi as jest.Mocked<typeof entitlementsApi>;
const reports = reportsApi as jest.Mocked<typeof reportsApi>;
const analytics = analyticsApi as jest.Mocked<typeof analyticsApi>;
const mockShare = shareAdvancedCsv as jest.MockedFunction<typeof shareAdvancedCsv>;

const PLUS: EntitlementsDto = { planCode: 'PLUS', planName: 'Plus', features: { ADVANCED_REPORTS: true } };
const FREE: EntitlementsDto = { planCode: 'FREE', planName: 'Free', features: {} };

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AdvancedReportsScreen />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const lastRequest = () => {
  const calls = analytics.advanced.mock.calls;
  return calls[calls.length - 1][0];
};
// The global stub in test/setup.ts: one object for every caller, so a press in the screen is seen here.
const nav = () => (jest.requireMock('@react-navigation/native') as { useNavigation: () => { navigate: jest.Mock; goBack: jest.Mock } }).useNavigation();

async function loaded() {
  await screen.findByTestId('kpi-income');
}
async function openTab(name: string) {
  fireEvent.press(screen.getByRole('tab', { name }));
  await act(async () => {});
}

beforeEach(() => {
  jest.clearAllMocks();
  entitlements.mine.mockResolvedValue(PLUS);
  reports.availableMonths.mockResolvedValue(['2025-01', '2025-02', '2026-08', '2026-09']);
  analytics.advanced.mockResolvedValue(fixtureReport());
  analytics.categoryConfidence.mockResolvedValue([{ category: 'Food', avgConfidence: 88, merchantCount: 4 }]);
  analytics.learningGrowth.mockResolvedValue([{ month: '2026-08', learnedCount: 10, correctedCount: 2 }]);
});

describe('AdvancedReportsScreen entitlement', () => {
  it('shows an upgrade prompt for a Free user, and asks the server for nothing', async () => {
    entitlements.mine.mockResolvedValue(FREE);
    renderScreen();

    expect(await screen.findByText(/Advanced Reports is a Plus & Premium feature/)).toBeTruthy();
    expect(analytics.advanced).not.toHaveBeenCalled();
    expect(screen.queryByRole('tablist')).toBeNull();
  });

  it('names only Plus in the upgrade prompt while Premium is hidden', async () => {
    mockPremium.visible = false;
    try {
      entitlements.mine.mockResolvedValue(FREE);
      renderScreen();
      expect(await screen.findByText(/Advanced Reports is a Plus feature/)).toBeTruthy();
      expect(screen.queryByText(/Premium/)).toBeNull();
    } finally {
      mockPremium.visible = true;
    }
  });

  it('fails closed for a user with no subscription at all', async () => {
    entitlements.mine.mockResolvedValue({ planCode: null, planName: null, features: {} });
    renderScreen();
    expect(await screen.findByText(/Advanced Reports is a Plus & Premium feature/)).toBeTruthy();
    expect(analytics.advanced).not.toHaveBeenCalled();
  });

  it('goes back when the back button is pressed', () => {
    entitlements.mine.mockReturnValue(new Promise(() => {}));
    renderScreen();
    fireEvent.press(screen.getByLabelText('Back'));
    expect(nav().goBack).toHaveBeenCalledTimes(1);
  });
});

describe('AdvancedReportsScreen for an entitled user', () => {
  it('asks for the last 12 months by default and shows the server figures with their changes', async () => {
    renderScreen();
    await loaded();

    expect(lastRequest()).toEqual({ range: 'LAST_12_MONTHS' });
    expect(screen.getByTestId('kpi-net')).toHaveTextContent(text('₹3,60,000'));
    expect(screen.getByTestId('kpi-income')).toHaveTextContent(text('₹6,00,000'));
    expect(screen.getByTestId('kpi-expense')).toHaveTextContent(text('₹2,40,000'));
    expect(screen.getByTestId('kpi-average')).toHaveTextContent(text('₹40,000'));
    expect(screen.getByTestId('kpi-savings')).toHaveTextContent(text('60%'));
    expect(screen.getByTestId('kpi-net-change')).toHaveTextContent(text('↑ ₹20,000 vs the previous 6 months'));
  });

  it('names the months in the period and what it is compared with', async () => {
    renderScreen();
    await loaded();
    const notice = screen.getByTestId('period-notice');
    expect(notice).toHaveTextContent(text('Apr 2026 – Sep 2026 · 6 months'));
    expect(notice).toHaveTextContent(text('Compared with Oct 2025 – Mar 2026.'));
  });

  it('asks again when the period is changed from the picker', async () => {
    renderScreen();
    await loaded();

    fireEvent.press(screen.getByLabelText(/Period: Last 12 months/));
    fireEvent.press(screen.getByText('Last 3 months'));

    await waitFor(() => expect(lastRequest()).toEqual({ range: 'LAST_3_MONTHS' }));
  });

  it('starts a custom run from the months on screen and asks for it only with both', async () => {
    renderScreen();
    await loaded();

    fireEvent.press(screen.getByLabelText(/Period: Last 12 months/));
    fireEvent.press(screen.getByText('Custom range'));

    await waitFor(() => expect(lastRequest()).toEqual({ range: 'CUSTOM', startMonth: '2026-04', endMonth: '2026-09' }));
    expect(screen.getByLabelText(/From: April 2026/)).toBeTruthy();
    expect(screen.getByLabelText(/To: September 2026/)).toBeTruthy();
  });

  it('lets a custom run be changed from the months the history has, newest first', async () => {
    renderScreen();
    await loaded();
    fireEvent.press(screen.getByLabelText(/Period: Last 12 months/));
    fireEvent.press(screen.getByText('Custom range'));
    await waitFor(() => expect(lastRequest().range).toBe('CUSTOM'));

    fireEvent.press(screen.getByLabelText(/From: April 2026/));
    fireEvent.press(await screen.findByText('February 2025'));

    await waitFor(() => expect(lastRequest()).toEqual({ range: 'CUSTOM', startMonth: '2025-02', endMonth: '2026-09' }));
  });

  it('does not ask for a custom run whose start is after its end, and says what it needs', async () => {
    renderScreen();
    await loaded();
    fireEvent.press(screen.getByLabelText(/Period: Last 12 months/));
    fireEvent.press(screen.getByText('Custom range'));
    await waitFor(() => expect(lastRequest().range).toBe('CUSTOM'));
    const calls = analytics.advanced.mock.calls.length;

    fireEvent.press(screen.getByLabelText(/To: September 2026/));
    fireEvent.press(await screen.findByText('February 2025'));

    expect(await screen.findByText(/Choose a start and an end month/)).toBeTruthy();
    expect(analytics.advanced.mock.calls.length).toBe(calls);
  });

  it('starts the comparison from its default again when the period changes', async () => {
    renderScreen();
    await loaded();
    fireEvent.press(screen.getByLabelText(/Compare with: Previous 6 months/));
    fireEvent.press(screen.getByText('Same months a year earlier'));
    await waitFor(() => expect(lastRequest().compare).toBe('SAME_PERIOD_LAST_YEAR'));

    fireEvent.press(screen.getByLabelText(/Period: Last 12 months/));
    fireEvent.press(screen.getByText('Last 3 months'));

    await waitFor(() => expect(lastRequest()).toEqual({ range: 'LAST_3_MONTHS' }));
  });

  it('offers the compare picker only when there is more than one way to compare', async () => {
    renderScreen();
    await loaded();
    fireEvent.press(screen.getByLabelText(/Compare with: Previous 6 months/));
    fireEvent.press(screen.getByText('Same months a year earlier'));
    await waitFor(() => expect(lastRequest()).toEqual({ range: 'LAST_12_MONTHS', compare: 'SAME_PERIOD_LAST_YEAR' }));
  });

  it('has no compare picker when the period has a single comparison', async () => {
    const base = fixtureReport();
    analytics.advanced.mockResolvedValue(fixtureReport({ period: { ...base.period, compareOptions: ['PREVIOUS_PERIOD'] } }));
    renderScreen();
    await loaded();
    expect(screen.queryByTestId('compare-field')).toBeNull();
  });

  it('shows no change lines, and says once why, when the server withheld the comparison', async () => {
    const base = fixtureReport();
    analytics.advanced.mockResolvedValue(fixtureReport({
      comparison: { ...base.comparison, reliable: false, gateReason: 'PRIOR_PERIOD_BEFORE_HISTORY', incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null, avgMonthlyExpenseDeltaPct: null, savingsRateDeltaPp: null },
    }));
    renderScreen();
    await loaded();

    expect(screen.queryByText(/vs the previous/)).toBeNull();
    expect(screen.getByTestId('period-notice')).toHaveTextContent(text('does not reach back far enough'));
    expect(screen.getByTestId('kpi-income')).toHaveTextContent(text('₹6,00,000'));
  });

  it('says which month was left out, and why', async () => {
    const base = fixtureReport();
    analytics.advanced.mockResolvedValue(fixtureReport({ period: { ...base.period, leftOutMonth: '2026-10', leftOutReason: 'PARTIAL_MONTH' } }));
    renderScreen();
    await loaded();
    expect(screen.getByTestId('period-notice')).toHaveTextContent(text('Oct 2026 is left out'));
  });

  it('withholds the savings rate with its reason instead of showing a number', async () => {
    const base = fixtureReport();
    analytics.advanced.mockResolvedValue(fixtureReport({ totals: { ...base.totals, savingsRatePct: null, savingsRateGateReason: 'UNRESOLVED_EXCEEDS_INCOME' } }));
    renderScreen();
    await loaded();
    expect(screen.getByTestId('kpi-savings')).toHaveTextContent(text('—'));
    expect(screen.getByText('Held back: unclassified credits')).toBeTruthy();
  });

  describe('when there is nothing to show', () => {
    const empty = (reason: string): AdvancedReport => {
      const base = fixtureReport();
      return fixtureReport({ period: { ...base.period, hasData: false, noDataReason: reason, startMonth: null, endMonth: null, monthCount: 0 }, months: [], insights: [] });
    };

    it.each([
      ['NO_TRANSACTIONS', 'No transactions yet'],
      ['ONLY_PARTIAL_MONTH', 'Your first month is still in progress'],
      ['NO_MONTHS_IN_PERIOD', 'No data in this period'],
    ])('explains %s in its own words', async (reason, title) => {
      analytics.advanced.mockResolvedValue(empty(reason));
      renderScreen();
      expect(await screen.findByText(title)).toBeTruthy();
      expect(screen.queryByTestId('advanced-kpis')).toBeNull();
    });
  });

  it('offers to try again when the first load fails', async () => {
    analytics.advanced.mockRejectedValueOnce(new Error('boom'));
    renderScreen();
    expect(await screen.findByText(/Couldn't load Advanced Reports/)).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    await loaded();
    expect(analytics.advanced).toHaveBeenCalledTimes(2);
  });

  it('refetches on pull to refresh', async () => {
    renderScreen();
    await loaded();
    const control = screen.UNSAFE_getByType(RefreshControl);
    await act(async () => { control.props.onRefresh(); });
    await waitFor(() => expect(analytics.advanced).toHaveBeenCalledTimes(2));
  });

  describe('tabs', () => {
    it('shows the overview: the chart, insights, categories, merchants and how you paid', async () => {
      renderScreen();
      await loaded();
      expect(screen.getByTestId('flow-chart')).toBeTruthy();
      expect(screen.getByTestId('insight-cards')).toBeTruthy();
      expect(screen.getByTestId('category-list')).toBeTruthy();
      expect(screen.getByTestId('merchant-list')).toBeTruthy();
      expect(screen.getByTestId('payment-methods')).toBeTruthy();
    });

    it('shows the spending tab with every category, the trend, and international spend', async () => {
      renderScreen();
      await loaded();
      await openTab('Spending');
      expect(screen.getByTestId('category-Travel')).toBeTruthy();
      expect(screen.getByTestId('category-other')).toHaveTextContent(text('Other (3 more)'));
      expect(screen.getByTestId('category-trend')).toBeTruthy();
      expect(screen.getByTestId('international')).toHaveTextContent(text('No international spend'));
    });

    it('shows the income tab: by source, by month and how steady', async () => {
      renderScreen();
      await loaded();
      await openTab('Income');
      expect(screen.getByTestId('income-sources')).toHaveTextContent(text('₹5,40,000'));
      expect(screen.getByTestId('income-steadiness')).toHaveTextContent(text('Income came in during 6 of 6 months.'));
    });

    it('shows the merchants tab with how much of the spending names a merchant', async () => {
      renderScreen();
      await loaded();
      await openTab('Merchants');
      expect(screen.getByTestId('merchant-coverage')).toHaveTextContent(text('62% of your spending is tied to a named merchant'));
    });

    it('takes the period pickers away on the categorisation tab, and does not ask for the period report there', async () => {
      renderScreen();
      await loaded();
      analytics.advanced.mockClear();

      await openTab('Category insights');

      expect(await screen.findByTestId('categorisation-intro')).toHaveTextContent(text('does not change with the period'));
      expect(screen.queryByTestId('period-field')).toBeNull();
      expect(await screen.findByText('Across 4 merchants')).toBeTruthy();
      expect(screen.getByText('10 learned · 2 corrected')).toBeTruthy();
    });

    it('says so for a user who has taught Fynora nothing', async () => {
      analytics.categoryConfidence.mockResolvedValue([]);
      analytics.learningGrowth.mockResolvedValue([]);
      renderScreen();
      await loaded();
      await openTab('Category insights');
      expect(await screen.findByText(/Nothing learned yet/)).toBeTruthy();
      expect(screen.getByText(/No learning history yet/)).toBeTruthy();
    });

    it('sends a view-all link on the overview to the full tab, when there is more than the overview shows', async () => {
      const base = fixtureReport();
      const extra = base.categories.rows.slice(0, 3).map((r, i) => ({ ...r, name: `More ${i}` }));
      analytics.advanced.mockResolvedValue(fixtureReport({ categories: { ...base.categories, rows: [...base.categories.rows, ...extra] } }));
      renderScreen();
      await loaded();
      fireEvent.press(screen.getByLabelText('View all categories'));
      await act(async () => {});
      expect(screen.getByRole('tab', { name: 'Spending' }).props.accessibilityState.selected).toBe(true);
    });
  });

  describe('drill-down', () => {
    it('opens Transactions for a category over the whole period', async () => {
      renderScreen();
      await loaded();
      await openTab('Spending');
      nav().navigate.mockClear();

      fireEvent.press(screen.getByLabelText(/Food & Dining: ₹60,000/));

      expect(nav().navigate).toHaveBeenCalledWith('Transactions', {
        filters: expect.objectContaining({ categoryName: 'Food & Dining', dateFrom: '2026-04-01', dateTo: '2026-09-30', label: 'Food & Dining' }),
      });
    });

    it('never filters by "Uncategorized", which is no category', async () => {
      const base = fixtureReport();
      analytics.advanced.mockResolvedValue(fixtureReport({ categories: { ...base.categories, rows: [{ ...base.categories.rows[0], name: 'Uncategorized' }] } }));
      renderScreen();
      await loaded();
      await openTab('Spending');
      nav().navigate.mockClear();

      fireEvent.press(screen.getByLabelText(/Uncategorized: ₹1,20,000/));

      const filters = nav().navigate.mock.calls[0][1].filters;
      expect(filters.categoryName).toBeUndefined();
      expect(filters.dateFrom).toBe('2026-04-01');
    });

    it('opens Transactions searching for a merchant over the period', async () => {
      renderScreen();
      await loaded();
      await openTab('Merchants');
      nav().navigate.mockClear();

      fireEvent.press(screen.getByLabelText(/Amazon: ₹30,000/));

      expect(nav().navigate).toHaveBeenCalledWith('Transactions', {
        filters: expect.objectContaining({ keyword: 'Amazon', dateFrom: '2026-04-01', dateTo: '2026-09-30' }),
      });
    });

    it('opens the transactions behind an insight', async () => {
      renderScreen();
      await loaded();
      nav().navigate.mockClear();

      fireEvent.press(screen.getByLabelText(/Shopping is lower/));

      expect(nav().navigate).toHaveBeenCalledWith('Transactions', {
        filters: expect.objectContaining({ categoryName: 'Shopping', dateFrom: '2026-04-01', dateTo: '2026-09-30' }),
      });
    });
  });

  describe('charts', () => {
    it('gives the same figures as a list, which is also what a screen reader is read', async () => {
      renderScreen();
      await loaded();
      expect(screen.getByLabelText(/Apr 2026: income ₹1,00,000, expenses ₹40,000, net ₹60,000/)).toBeTruthy();

      fireEvent.press(screen.getAllByLabelText('Show as a list')[0]);

      const list = screen.getByTestId('flow-list');
      expect(within(list).getByLabelText(/Apr 2026: income ₹1,00,000/)).toBeTruthy();
      expect(screen.queryByTestId('flow-chart')).toBeNull();
    });

    it('marks a month that is only part of a month, in the chart and in words', async () => {
      const base = fixtureReport();
      analytics.advanced.mockResolvedValue(fixtureReport({
        months: base.months.map((m) => (m.month === '2026-05' ? fixtureMonth('2026-05', { complete: false }) : m)),
        period: { ...base.period, incompleteMonths: ['2026-05'] },
      }));
      renderScreen();
      await loaded();
      expect(screen.getByTestId('flow-partial-note')).toHaveTextContent(text('Only part of the month is in'));
      expect(screen.getByText(/May 2026 is only part of a month/)).toBeTruthy();
    });

    it('draws a period longer than a year one calendar year at a time, and says so', async () => {
      const months = Array.from({ length: 18 }, (_, i) => fixtureMonth(`${2025 + Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, '0')}`));
      const base = fixtureReport();
      analytics.advanced.mockResolvedValue(fixtureReport({ months, period: { ...base.period, monthCount: 18, startMonth: '2025-01', endMonth: '2026-06' } }));
      renderScreen();
      await loaded();

      expect(screen.getByLabelText(/^2025: income ₹12,00,000/)).toBeTruthy();
      expect(screen.getByTestId('flow-partial-note')).toHaveTextContent(text('Only part of the year is in the period'));

      fireEvent.press(screen.getByLabelText('Show monthly'));
      expect(screen.getByLabelText(/Jan 2025: income/)).toBeTruthy();
    });
  });

  describe('export', () => {
    it('hands the report on display to the share sheet', async () => {
      renderScreen();
      await loaded();
      fireEvent.press(screen.getByLabelText('Export as CSV'));
      await act(async () => {});
      expect(mockShare).toHaveBeenCalledTimes(1);
      expect(mockShare.mock.calls[0][0].period.startMonth).toBe('2026-04');
    });

    it('says so when the share fails, and can be tried again', async () => {
      mockShare.mockRejectedValueOnce(new Error('sheet refused'));
      renderScreen();
      await loaded();
      fireEvent.press(screen.getByLabelText('Export as CSV'));
      expect(await screen.findByText(/Could not export this report as CSV/)).toBeTruthy();
      fireEvent.press(screen.getByLabelText('Export as CSV'));
      await act(async () => {});
      expect(mockShare).toHaveBeenCalledTimes(2);
    });

    it('cannot export when there is nothing to export', async () => {
      const base = fixtureReport();
      analytics.advanced.mockResolvedValue(fixtureReport({ period: { ...base.period, hasData: false, noDataReason: 'NO_TRANSACTIONS' }, months: [] }));
      renderScreen();
      await screen.findByText('No transactions yet');
      expect(screen.getByLabelText('Export as CSV').props.accessibilityState.disabled).toBe(true);
    });
  });
});
