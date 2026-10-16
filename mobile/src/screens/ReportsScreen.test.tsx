import { RefreshControl } from 'react-native';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { ReportsScreen } from './ReportsScreen';
import {
  analyticsApi, categoriesApi, entitlementsApi, insightsApi, reportsApi, type InsightsData, type ReportData,
} from '../api/endpoints';
import { shareCsv, sharePdf } from '../lib/reportExport';

jest.mock('../api/endpoints', () => ({
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn(), trend: jest.fn() },
  categoriesApi: { list: jest.fn() },
  insightsApi: { get: jest.fn() },
  entitlementsApi: { mine: jest.fn() },
  analyticsApi: { topMerchants: jest.fn() },
}));

// The export builders are tested directly in reportExport.test.ts; what matters here is that the
// screen reaches them with the report actually on display, and survives a refusal.
jest.mock('../lib/reportExport', () => ({
  shareCsv: jest.fn(),
  sharePdf: jest.fn(),
}));

const api = reportsApi as jest.Mocked<typeof reportsApi>;
const mockCategories = categoriesApi as jest.Mocked<typeof categoriesApi>;
const mockInsights = insightsApi as jest.Mocked<typeof insightsApi>;
const mockEntitlements = entitlementsApi as jest.Mocked<typeof entitlementsApi>;
const mockAnalytics = analyticsApi as jest.Mocked<typeof analyticsApi>;
const mockShareCsv = shareCsv as jest.MockedFunction<typeof shareCsv>;
const mockSharePdf = sharePdf as jest.MockedFunction<typeof sharePdf>;

const MONTHS = ['2026-05', '2026-06', '2026-07'];

function reportFor(month: string, over: Partial<ReportData> = {}): ReportData {
  return {
    month,
    income: 82000,
    expense: 51500,
    categories: [{ category: 'Groceries', amount: 12000 }],
    ...over,
  };
}

const cat = (name: string) => ({ id: `id-${name}`, name, isSystem: false, icon: 'tag', color: 'blue' });
const insights = (over: Record<string, unknown> = {}) =>
  ({ sentences: [], movers: [], reportingMonth: '2026-07', reportingMonthIsCurrent: false, ...over }) as unknown as InsightsData;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReportsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

/**
 * Waits for the REPORT, not just the month list. The two load independently, and the month picker
 * appears first -- keying off it presses the export button while it is still correctly disabled.
 */
async function loadedReport() {
  await screen.findByTestId('hero-net');
}

async function openTab(name: 'Overview' | 'Spending' | 'Income') {
  fireEvent.press(screen.getByRole('tab', { name }));
  await settle();
}

beforeEach(() => {
  api.availableMonths.mockReset().mockResolvedValue(MONTHS);
  api.forMonth.mockReset().mockImplementation(async (m: string) => reportFor(m));
  api.trend.mockReset().mockResolvedValue([]);
  mockCategories.list.mockReset().mockResolvedValue([cat('Groceries')] as never);
  mockInsights.get.mockReset().mockResolvedValue(insights());
  // No Advanced Reports unless a test says otherwise, so the paid endpoint is never called by default.
  mockEntitlements.mine.mockReset().mockResolvedValue({ features: { ADVANCED_REPORTS: false } } as never);
  mockAnalytics.topMerchants.mockReset().mockResolvedValue([]);
  mockShareCsv.mockReset().mockResolvedValue(undefined);
  mockSharePdf.mockReset().mockResolvedValue(undefined);
});

describe('ReportsScreen', () => {
  // The server returns months ascending; the newest is the one anyone opening this screen wants.
  it('opens on the most recent month', async () => {
    renderScreen();

    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-07'));
    expect(await screen.findByLabelText(/Month: Jul 26/)).toBeTruthy();
  });

  it('switches months on selection', async () => {
    renderScreen();
    await screen.findByLabelText(/Month: Jul 26/);

    fireEvent.press(screen.getByLabelText(/Month: Jul 26/));
    await settle();
    fireEvent.press(screen.getByText('May 2026'));
    await settle();

    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-05'));
    expect(await screen.findByLabelText(/Month: May 26/)).toBeTruthy();
  });

  // The picker's rows must show something a person actually reads ("August 2026"), not the raw
  // "YYYY-MM" the screen's queries key on -- OptionPickerModal renders each option string verbatim
  // with no formatting of its own, so ReportsScreen has to hand it already-formatted labels.
  it('shows formatted month labels in the picker, not raw YYYY-MM strings', async () => {
    renderScreen();
    await screen.findByLabelText(/Month: Jul 26/);

    fireEvent.press(screen.getByLabelText(/Month: Jul 26/));
    await settle();

    expect(screen.getByText('June 2026')).toBeTruthy();
    expect(screen.getByText('May 2026')).toBeTruthy();
    expect(screen.queryByText('2026-07')).toBeNull();
    expect(screen.queryByText('2026-06')).toBeNull();
    expect(screen.queryByText('2026-05')).toBeNull();
  });

  // The category row's spoken label used to hardcode "this month's spending" regardless of which
  // month was on screen -- so picking a past month like May still had VoiceOver announce May's
  // figures as "this month's spending", flatly wrong once the picker is used for anything but the
  // default.
  it('names the picked month in the category row label instead of hardcoding "this month"', async () => {
    renderScreen();
    await screen.findByLabelText(/Month: Jul 26/);

    fireEvent.press(screen.getByLabelText(/Month: Jul 26/));
    await settle();
    fireEvent.press(screen.getByText('May 2026'));
    await settle();
    await openTab('Spending');

    expect(
      await screen.findByLabelText(/Groceries: ₹12,000, 100 percent of May 2026's spending/)
    ).toBeTruthy();
  });

  // There is nothing to export until the report itself has arrived, so the button stays disabled
  // rather than producing an empty file.
  it('does not offer an export before the report has loaded', async () => {
    // Held pending on purpose: with an instantly-resolving mock the report is already on screen by
    // the time the button can be queried, and the disabled state this asserts never exists.
    api.forMonth.mockReset().mockReturnValue(new Promise(() => {}));
    renderScreen();

    const button = await screen.findByLabelText('Export as CSV');
    expect(button.props.accessibilityState.disabled).toBe(true);
  });

  it('exports the month on screen as CSV', async () => {
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByLabelText('Export as CSV'));
    await settle();

    await waitFor(() => expect(mockShareCsv).toHaveBeenCalledTimes(1));
    expect(mockShareCsv.mock.calls[0][0].month).toBe('2026-07');
  });

  it('exports the month on screen as PDF', async () => {
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByLabelText('Export as PDF'));
    await settle();

    await waitFor(() => expect(mockSharePdf).toHaveBeenCalledTimes(1));
    expect(mockSharePdf.mock.calls[0][0].month).toBe('2026-07');
  });

  // Sharing is unavailable on some devices and the user can dismiss the sheet -- neither should
  // look like the report itself failed to load.
  it('reports an export failure inline without losing the report', async () => {
    mockShareCsv.mockReset().mockRejectedValue(new Error('Sharing is not available on this device.'));
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByLabelText('Export as CSV'));
    await settle();

    expect(await screen.findByText(/Could not export this report as CSV/)).toBeTruthy();
    expect(screen.getByTestId('hero-net')).toBeTruthy();
  });

  it('points at the import flow when there are no months yet', async () => {
    api.availableMonths.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/import a statement to see reports/i)).toBeTruthy();
    expect(api.forMonth).not.toHaveBeenCalled();
  });

  it('says the report could not be loaded rather than showing a month of zeroes', async () => {
    api.forMonth.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load July 2026's report/)).toBeTruthy();
  });

  describe('skeleton loading', () => {
    it('shows a skeleton shell instead of a spinner while the month list is loading', () => {
      api.availableMonths.mockReset().mockReturnValue(new Promise(() => {}));
      renderScreen();

      expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
    });

    it('skeletons the report body on an uncached month, keeping the month picker usable', async () => {
      renderScreen();
      await loadedReport();

      api.forMonth.mockReset().mockReturnValue(new Promise(() => {}));
      fireEvent.press(screen.getByLabelText(/Month: Jul 26/));
      await settle();
      fireEvent.press(screen.getByText('May 2026'));
      await settle();

      expect(screen.getAllByTestId('shimmer-block', { hidden: true }).length).toBeGreaterThan(0);
      // The month picker -- part of the shell -- must stay mounted and usable while the new
      // month's report is still in flight.
      expect(screen.getByLabelText(/Month: May 26/)).toBeTruthy();
    });
  });
});

describe('the month arrows', () => {
  it('step back and forward through the months, asking the server for each', async () => {
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByLabelText('Previous month'));
    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-06'));
    expect(await screen.findByLabelText(/Month: Jun 26/)).toBeTruthy();

    fireEvent.press(screen.getByLabelText('Next month'));
    expect(await screen.findByLabelText(/Month: Jul 26/)).toBeTruthy();
  });

  it('are disabled at either end instead of wrapping', async () => {
    renderScreen();
    await loadedReport();
    expect(screen.getByLabelText('Next month').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByLabelText('Previous month').props.accessibilityState.disabled).toBe(false);

    fireEvent.press(screen.getByLabelText('Previous month'));
    await settle();
    fireEvent.press(screen.getByLabelText('Previous month'));
    await settle();
    await screen.findByLabelText(/Month: May 26/);
    expect(screen.getByLabelText('Previous month').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByLabelText('Next month').props.accessibilityState.disabled).toBe(false);
  });

  it('are both disabled for a single month', async () => {
    api.availableMonths.mockReset().mockResolvedValue(['2026-07']);
    renderScreen();
    await loadedReport();
    expect(screen.getByLabelText('Previous month').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByLabelText('Next month').props.accessibilityState.disabled).toBe(true);
  });
});

describe('the tabs', () => {
  it('open on Overview, and show only that tab\'s sections', async () => {
    renderScreen();
    await loadedReport();

    expect(screen.getByRole('tab', { name: 'Overview' }).props.accessibilityState.selected).toBe(true);
    expect(screen.queryByTestId('category-rows')).toBeNull();

    await openTab('Spending');
    expect(screen.getByRole('tab', { name: 'Spending' }).props.accessibilityState.selected).toBe(true);
    expect(await screen.findByTestId('category-rows')).toBeTruthy();
    expect(screen.queryByTestId('hero-net')).toBeNull();
  });

  it('keep the chosen tab when the month changes', async () => {
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');

    fireEvent.press(screen.getByLabelText('Previous month'));
    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-06'));
    expect(await screen.findByTestId('category-rows')).toBeTruthy();
  });
});

/**
 * The backend builds report.expense and report.categories from different transaction sets on
 * purpose: ReportService narrows to excludingInvestmentTransfers(txns) for the income/expense
 * totals, while byCategory keeps the wider list "so an Investments line still shows up in the
 * report's own category table". Dividing a category by report.expense therefore divided some rows
 * by a total they were excluded from -- yielding shares over 100% which the bar then clamped, so
 * two rows of very different size both rendered as full-width bars.
 */
describe('category shares', () => {
  it('keeps shares within 100% when the month contains an investment transfer', async () => {
    api.availableMonths.mockReset().mockResolvedValue(['2026-07']);
    // Both are real categories, so both rows are pressable and carry the spoken label asserted below.
    mockCategories.list.mockResolvedValue([cat('Investments'), cat('Groceries')] as never);
    api.forMonth.mockReset().mockResolvedValue({
      month: '2026-07',
      income: 100000,
      // Deliberately LESS than the categories below sum to -- the SIP is excluded from the total
      // but still listed as a category, exactly as the backend produces it.
      expense: 2000,
      categories: [
        { category: 'Investments', amount: 3000 },
        { category: 'Groceries', amount: 2000 },
      ],
    } as never);

    renderScreen();
    await loadedReport();
    await openTab('Spending');

    // 3000 / 5000 and 2000 / 5000 -- shares of the breakdown they actually belong to, not of a
    // total one of them was excluded from (which gave 150% and 100%).
    expect(await screen.findByLabelText(/Investments: ₹3,000, 60 percent of July 2026's spending/)).toBeTruthy();
    expect(screen.getByLabelText(/Groceries: ₹2,000, 40 percent of July 2026's spending/)).toBeTruthy();
  });
});

describe('drill-through into the ledger (Track C/C4)', () => {
  it('opens Transactions filtered to this category and the whole month currently on screen', async () => {
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    fireEvent.press(screen.getByLabelText(/Groceries: ₹12,000/));

    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({
        categoryName: 'Groceries', dateFrom: '2026-07-01', dateTo: '2026-07-31', label: 'Groceries · Jul 26',
      }),
    });
  });

  it('is not pressable for a category the ledger cannot filter to (the catch-all Uncategorized)', async () => {
    api.forMonth.mockReset().mockImplementation(async (m: string) => reportFor(m, {
      categories: [{ category: 'Groceries', amount: 12000 }, { category: 'Uncategorized', amount: 3000 }],
    }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');

    expect(screen.getByText('Uncategorized')).toBeTruthy();
    expect(screen.queryByLabelText(/Uncategorized: ₹3,000/)).toBeNull();
  });

  it('opens a day, and a biggest purchase on its day, in Transactions', async () => {
    api.forMonth.mockReset().mockImplementation(async (m: string) => reportFor(m, {
      dailySpend: [{ date: '2026-07-14', amount: 3120 }, { date: '2026-07-03', amount: 400 }],
      biggestSpends: [{ id: 'a', date: '2026-07-14', label: 'Big Mart', category: 'Groceries', amount: 3120 }],
    }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('biggest-spends');
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    fireEvent.press(screen.getByLabelText('Jul 14: ₹3,120 spent'));
    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({ dateFrom: '2026-07-14', dateTo: '2026-07-14' }),
    });

    navigate.mockClear();
    fireEvent.press(screen.getByLabelText(/Big Mart, ₹3,120 on Jul 14/));
    expect(navigate).toHaveBeenCalledWith('Transactions', {
      filters: expect.objectContaining({ keyword: 'Big Mart', dateFrom: '2026-07-14', dateTo: '2026-07-14' }),
    });
  });

  it('refreshes the trend and the insights as well as the report on a pull', async () => {
    renderScreen();
    await loadedReport();
    await waitFor(() => expect(mockInsights.get).toHaveBeenCalledTimes(1));
    expect(api.trend).toHaveBeenCalledTimes(1);
    expect(api.forMonth).toHaveBeenCalledTimes(1);

    await act(async () => screen.UNSAFE_getByType(RefreshControl).props.onRefresh());

    await waitFor(() => expect(api.forMonth).toHaveBeenCalledTimes(2));
    expect(api.trend).toHaveBeenCalledTimes(2);
    expect(mockInsights.get).toHaveBeenCalledTimes(2);
  });

  it('opens a purchase the server could not name on its day alone, with no search word', async () => {
    api.forMonth.mockReset().mockImplementation(async (m: string) => reportFor(m, {
      biggestSpends: [{ id: 'b', date: '2026-07-09', label: 'Transaction', category: 'Uncategorized', amount: 900 }],
    }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('biggest-spends');
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();

    fireEvent.press(screen.getByLabelText(/Transaction, ₹900 on Jul 9/));
    const [, params] = navigate.mock.calls[0] as [string, { filters: Record<string, unknown> }];
    expect(params.filters).toEqual(expect.objectContaining({ dateFrom: '2026-07-09', dateTo: '2026-07-09' }));
    expect(params.filters.keyword).toBeUndefined();
  });
});

// D3 (Track D security cleanup). Category breakdowns/totals are as screenshot-attractive as
// anything on the Dashboard or Accounts screen, which already guard against this.
describe('screen capture protection (Track D/D3)', () => {
  it('calls usePreventScreenCapture on mount', () => {
    renderScreen();

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });
});

describe('money not counted as income', () => {
  it('says how much came in but is not counted as income, once, with a way to sort it out', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { unresolvedInflow: 84500, unresolvedInflowCount: 3 }));
    renderScreen();
    await loadedReport();

    expect(screen.getByText("3 credits worth ₹84,500 aren't counted as income yet.")).toBeOnTheScreen();
    expect(screen.getAllByText(/counted as income yet/)).toHaveLength(1);
  });

  it('reads whole in the singular, and without a count from an older server', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { unresolvedInflow: 700, unresolvedInflowCount: 1 }));
    const first = renderScreen();
    expect(await screen.findByText("1 credit worth ₹700 isn't counted as income yet.")).toBeOnTheScreen();
    first.unmount();

    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { unresolvedInflow: 700 }));
    renderScreen();
    expect(await screen.findByText("₹700 received isn't counted as income yet.")).toBeOnTheScreen();
  });

  it('opens the review screen for the month (Plan 2)', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { unresolvedInflow: 700 }));
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();
    renderScreen();
    await loadedReport();

    fireEvent.press(screen.getByRole('button', { name: 'Review money not counted as income yet' }));
    expect(navigate).toHaveBeenCalledWith('MoneyReview', { start: '2026-07-01', end: '2026-07-31' });
  });

  it('lists income by kind on the Income tab (Plan 2), and none for a single line', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, {
      incomeByKind: [{ label: 'Salary', amount: 72000 }, { label: 'Family support', amount: 10000 }],
    }));
    const first = renderScreen();
    await loadedReport();
    await openTab('Income');
    expect(await screen.findByText('Income by kind')).toBeOnTheScreen();
    expect(screen.getByText('Family support')).toBeOnTheScreen();
    first.unmount();

    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { incomeByKind: [{ label: 'Salary', amount: 82000 }] }));
    renderScreen();
    await loadedReport();
    await openTab('Income');
    expect(screen.queryByText('Income by kind')).not.toBeOnTheScreen();
    expect(await screen.findByText('Salary')).toBeOnTheScreen();
  });

  it('says plainly when no income was counted, on the Income tab', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { income: 0, expense: 800 }));
    renderScreen();
    await loadedReport();
    await openTab('Income');
    expect(await screen.findByText('No income was counted in July 2026.')).toBeOnTheScreen();
  });

  it('says nothing when the server reports nothing unresolved', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { unresolvedInflow: 0 }));
    renderScreen();
    await loadedReport();

    expect(screen.queryByText(/counted as income yet/)).not.toBeOnTheScreen();
  });
});

describe('the hero and the stat cards', () => {
  const cmp = (over: Record<string, unknown> = {}) => ({
    previousMonth: '2026-06', reliable: true, gateReason: null, previousIncome: 70000, previousExpense: 60000,
    incomeDeltaPct: 17.1, expenseDeltaPct: -14.2, netDelta: 40500, ...over,
  });

  it('says what share of income was kept, the net as a signed amount, and how it moved', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { savingsRatePct: 37.2, savingsRateGateReason: null, comparison: cmp() as never }));
    renderScreen();

    expect(await screen.findByTestId('hero-headline')).toHaveTextContent('You kept 37% of what you earned.');
    expect(screen.getByTestId('hero-net')).toHaveTextContent('+₹30,500');
    expect(screen.getByTestId('hero-compare')).toHaveTextContent('₹40,500 more than June.');
    expect(screen.getByLabelText('Savings rate 37%')).toBeTruthy();
    expect(screen.getByLabelText('Of ₹82,000 earned, ₹51,500 spent and ₹30,500 kept')).toBeTruthy();
  });

  it('shows the fall in expenses as good and the rise in income as good, from the server\'s figures', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { comparison: cmp() as never }));
    renderScreen();
    await loadedReport();

    expect(within(screen.getByTestId('stat-income')).getByText('▲ 17.1%')).toBeTruthy();
    expect(within(screen.getByTestId('stat-expense')).getByText('▼ 14.2%')).toBeTruthy();
    expect(within(screen.getByTestId('stat-income')).getByText('vs June')).toBeTruthy();
  });

  it('leaves the delta off and says why once when the comparison is not trusted, even if figures arrive', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, {
      comparison: cmp({ reliable: false, gateReason: 'COVERAGE_GAP', incomeDeltaPct: 900, expenseDeltaPct: -90, netDelta: 123 }) as never,
    }));
    renderScreen();

    expect(await screen.findByTestId('hero-compare-note')).toHaveTextContent(/A statement is missing around July or June/);
    expect(screen.queryByText(/▲|▼/)).toBeNull();
    expect(screen.queryByText(/vs June/)).toBeNull();
    expect(screen.queryByTestId('hero-compare')).toBeNull();
  });

  it('explains why there is no rate, and draws neither the ring nor the bar', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, {
      income: 500, expense: 100, savingsRatePct: null, savingsRateGateReason: 'UNRESOLVED_EXCEEDS_INCOME', unresolvedInflow: 3000,
    }));
    renderScreen();

    expect(await screen.findByTestId('hero-headline'))
      .toHaveTextContent("Money you received hasn't been sorted into income yet, so there is no savings rate.");
    expect(screen.queryByTestId('hero-ring')).toBeNull();
    expect(screen.queryByTestId('hero-bar')).toBeNull();
  });

  it('says it in words, with no bar, when spending outran income', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { income: 1000, expense: 1500, savingsRatePct: -50 }));
    renderScreen();

    expect(await screen.findByTestId('hero-headline')).toHaveTextContent('You spent 50% more than you earned.');
    expect(screen.getByTestId('hero-net')).toHaveTextContent('-₹500');
    expect(screen.queryByTestId('hero-bar')).toBeNull();
  });

  it('never shows a signed zero for a net under half a rupee', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { income: 1000, expense: 1000.4, categories: [], savingsRatePct: -0.04 }));
    renderScreen();
    expect(await screen.findByTestId('hero-net')).toHaveTextContent(/^₹0$/);
  });

  it('keeps a thousands-of-percent rate inside the ring by letting its figure shrink', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { income: 500, expense: 6000, categories: [], savingsRatePct: -1100 }));
    renderScreen();
    const ring = await screen.findByTestId('hero-ring');
    expect(ring).toHaveProp('accessibilityLabel', 'Savings rate -1100%');
    expect(within(ring).getByText('-1100%')).toHaveProp('adjustsFontSizeToFit', true);
  });

  it('says nothing about a rate from an older server, and still shows the net', async () => {
    renderScreen();
    expect(await screen.findByTestId('hero-net')).toHaveTextContent('+₹30,500');
    expect(screen.queryByTestId('hero-headline')).toBeNull();
    expect(screen.queryByTestId('hero-ring')).toBeNull();
  });

  it('says a month only partly in may change, and only when no comparison note already says so', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, { complete: false }));
    const first = renderScreen();
    expect(await screen.findByTestId('hero-partial')).toHaveTextContent(/Part of this month isn't in yet/);
    first.unmount();

    api.forMonth.mockImplementation(async (m: string) => reportFor(m, {
      complete: false, comparison: cmp({ reliable: false, gateReason: 'SELECTED_MONTH_PARTIAL', netDelta: null, incomeDeltaPct: null, expenseDeltaPct: null }) as never,
    }));
    renderScreen();
    expect(await screen.findByTestId('hero-compare-note')).toBeTruthy();
    expect(screen.queryByTestId('hero-partial')).toBeNull();
  });
});

describe('the last months', () => {
  const point = (month: string, income: number, expense: number, complete = true) =>
    ({ month, income, expense, transactionCount: 10, complete });

  it('draws income and expenses, with the average of the whole months', async () => {
    api.trend.mockResolvedValue([point('2026-05', 1000, 400), point('2026-06', 1000, 600), point('2026-07', 1000, 500)]);
    renderScreen();

    expect((await screen.findByTestId('trend-chart')).props.accessibilityLabel).toContain('May: income ₹1,000, expenses ₹400');
    expect(screen.getByTestId('trend-average-note')).toHaveTextContent(/average spend, ₹500/);
    expect(screen.queryByTestId('trend-partial-note')).toBeNull();
    expect(api.trend).toHaveBeenCalledWith('2026-07', 6);
  });

  it('marks a partly-in month, says so, and leaves it out of the average', async () => {
    api.trend.mockResolvedValue([point('2026-05', 1000, 400), point('2026-06', 1000, 600), point('2026-07', 1000, 50, false)]);
    renderScreen();

    expect(await screen.findByTestId('trend-partial-note')).toHaveTextContent(/Only part of the month is in/);
    expect(screen.getByTestId('trend-average-note')).toHaveTextContent(/₹500/);
  });

  it('is not drawn for a single month, or when it fails to load', async () => {
    api.trend.mockResolvedValue([point('2026-07', 1000, 500)]);
    const first = renderScreen();
    await loadedReport();
    expect(screen.queryByTestId('trend-chart')).toBeNull();
    first.unmount();

    api.trend.mockRejectedValue(new Error('boom'));
    renderScreen();
    await loadedReport();
    expect(screen.queryByTestId('trend-chart')).toBeNull();
  });
});

describe('the spending tab', () => {
  const mover = (category: string, pctChange: number | null, current = 100, priorAverage = 80) =>
    ({ category, current, priorAverage, pctChange });

  it('shows each category\'s change against the recent average, with spending up as the bad direction', async () => {
    mockCategories.list.mockResolvedValue([cat('Shopping'), cat('Food')] as never);
    mockInsights.get.mockResolvedValue(insights({ movers: [mover('Shopping', -25.4), mover('Food', 12.2)] }));
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, {
      categories: [{ category: 'Shopping', amount: 600 }, { category: 'Food', amount: 400 }],
    }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    const chips = await screen.findAllByTestId('category-change');
    expect(chips.map((chip) => within(chip).getByText(/▲|▼/).props.children)).toEqual(['▼ 25%', '▲ 12%']);
  });

  it('shows no chip for a category with no history, and none when insights are unavailable', async () => {
    mockInsights.get.mockResolvedValue(insights({ movers: [mover('Groceries', null)] }));
    const first = renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('category-change')).toBeNull();
    first.unmount();

    mockInsights.get.mockRejectedValue(new Error('boom'));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    expect(await screen.findByTestId('category-rows')).toBeTruthy();
    expect(screen.queryByTestId('category-change')).toBeNull();
  });

  it('ignores insights that describe a different month than the one on screen', async () => {
    mockInsights.get.mockResolvedValue(insights({ reportingMonth: '2026-06', movers: [mover('Groceries', 40)] }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    await screen.findByTestId('category-rows');
    await waitFor(() => expect(mockInsights.get).toHaveBeenCalledWith('2026-07'));
    expect(screen.queryByTestId('category-change')).toBeNull();
  });

  it('keeps the page whole when the categories or the insights fail to load', async () => {
    mockCategories.list.mockRejectedValue(new Error('boom'));
    mockInsights.get.mockRejectedValue(new Error('boom'));
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    expect(await screen.findByTestId('category-rows')).toBeTruthy();
    expect(screen.getByText('Groceries')).toBeTruthy();
  });

  it('lists the top eight and opens the rest on request', async () => {
    api.forMonth.mockImplementation(async (m: string) => reportFor(m, {
      categories: Array.from({ length: 11 }, (_, i) => ({ category: `Cat ${i + 1}`, amount: 1000 - i * 10 })),
    }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Cat 9')).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Show all 11 categories' }));
    expect(await screen.findByText('Cat 11')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Show fewer' }));
    expect(screen.queryByText('Cat 11')).toBeNull();
  });

  it('leads with the biggest move and is not drawn when there is nothing to say', async () => {
    mockInsights.get.mockResolvedValue(insights({ movers: [mover('Food', 20, 1200, 1000), mover('Shopping', -40, 600, 1000)] }));
    const first = renderScreen();
    await loadedReport();
    await openTab('Spending');
    expect(await screen.findByTestId('observation-lead')).toHaveTextContent('Shopping is 40% below your recent average (₹600 against ₹1,000).');
    first.unmount();

    mockInsights.get.mockResolvedValue(insights({ movers: [mover('Food', 3)] }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Worth noticing')).toBeNull();
  });

  it('draws the calendar and the biggest purchases only when the server sent them', async () => {
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('spend-calendar')).toBeNull();
    expect(screen.queryByTestId('biggest-spends')).toBeNull();
  });
});

describe('biggest changes since the month before', () => {
  const cmp = (over: Record<string, unknown> = {}) => ({
    previousMonth: '2026-06', reliable: true, gateReason: null, previousIncome: 70000, previousExpense: 60000,
    incomeDeltaPct: 17.1, expenseDeltaPct: -14.2, netDelta: 40500, ...over,
  });
  const julyCats = [{ category: 'Rent', amount: 25000 }, { category: 'Food', amount: 9000 }, { category: 'Gifts', amount: 380 }];
  const juneCats = [{ category: 'Rent', amount: 23000 }, { category: 'Food', amount: 12000 }, { category: 'Gym', amount: 1200 }];

  const serve = (current: Record<string, unknown>, prior: ReportData | Error) =>
    api.forMonth.mockImplementation(async (m: string) => {
      if (m === '2026-07') return reportFor(m, { categories: julyCats, ...current });
      if (prior instanceof Error) throw prior;
      return prior;
    });

  it('lists the categories that moved most, in rupees, with their direction', async () => {
    serve({ comparison: cmp() as never }, reportFor('2026-06', { categories: juneCats }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    const card = await screen.findByTestId('category-changes');
    expect(within(card).getByText('Biggest changes since June')).toBeTruthy();
    expect(within(card).getByLabelText('Food, -₹3,000. ₹9,000 now, ₹12,000 in June')).toBeTruthy();
    expect(within(card).getByLabelText('Rent, +₹2,000. ₹25,000 now, ₹23,000 in June')).toBeTruthy();
    expect(within(card).getByLabelText('Gym, -₹1,200. Nothing this month, ₹1,200 in June')).toBeTruthy();
    expect(within(card).getByLabelText('Gifts, +₹380. New this month, nothing in June')).toBeTruthy();
    expect(api.forMonth).toHaveBeenCalledWith('2026-06');
  });

  it('does not ask for the month before, or draw the list, when the server did not trust the comparison', async () => {
    serve({ comparison: cmp({ reliable: false, gateReason: 'PARTIAL_PRIOR_MONTH', incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null }) as never }, reportFor('2026-06', { categories: juneCats }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');

    expect(api.forMonth).not.toHaveBeenCalledWith('2026-06');
    expect(screen.queryByTestId('category-changes')).toBeNull();
  });

  it('leaves the list out, and the screen alone, when the month before cannot be loaded', async () => {
    serve({ comparison: cmp() as never }, new Error('down'));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');

    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-06'));
    expect(screen.queryByTestId('category-changes')).toBeNull();
  });

  it('draws nothing when no category moved, or when the answer is for some other month', async () => {
    serve({ comparison: cmp() as never }, reportFor('2026-06', { categories: julyCats }));
    const { unmount } = renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');
    await waitFor(() => expect(api.forMonth).toHaveBeenCalledWith('2026-06'));
    expect(screen.queryByTestId('category-changes')).toBeNull();
    unmount();

    serve({ comparison: cmp() as never }, reportFor('2026-04', { categories: juneCats }));
    renderScreen();
    await loadedReport();
    await openTab('Spending');
    await screen.findByTestId('category-rows');
    await settle();
    expect(screen.queryByTestId('category-changes')).toBeNull();
  });
});

describe('top merchants', () => {
  it('shows a locked preview with no real data, and never asks the paid endpoint, without Advanced Reports', async () => {
    mockAnalytics.topMerchants.mockResolvedValue([{ merchantId: 'm1', merchantName: 'Secret Merchant', totalSpend: 99999, transactionCount: 3 }]);
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    expect(await screen.findByTestId('locked-merchants')).toBeTruthy();
    // The stand-in bars are decorative, so they are hidden from assistive tech (and from default queries).
    expect(screen.getByTestId('locked-merchants-bars', { hidden: true })).toBeTruthy();
    expect(screen.getByText('Plus & Premium')).toBeTruthy();
    expect(mockAnalytics.topMerchants).not.toHaveBeenCalled();
    expect(screen.queryByText('Secret Merchant')).toBeNull();

    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();
    fireEvent.press(screen.getByRole('button', { name: 'View plans' }));
    expect(navigate).toHaveBeenCalledWith('Subscription');
  });

  it('shows the real top merchants for the month, with a way into Advanced Reports, with it', async () => {
    mockEntitlements.mine.mockResolvedValue({ features: { ADVANCED_REPORTS: true } } as never);
    mockAnalytics.topMerchants.mockResolvedValue([
      { merchantId: 'm1', merchantName: 'Big Mart', totalSpend: 9000, transactionCount: 4 },
      { merchantId: 'm2', merchantName: 'Corner Cafe', totalSpend: 1200, transactionCount: 1 },
    ]);
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    expect(await screen.findByTestId('top-merchants')).toBeTruthy();
    expect(screen.getByText('Big Mart')).toBeTruthy();
    expect(screen.getByText('4 transactions')).toBeTruthy();
    expect(screen.getByText('1 transaction')).toBeTruthy();
    expect(mockAnalytics.topMerchants).toHaveBeenCalledWith('2026-07');
    expect(screen.queryByTestId('locked-merchants')).toBeNull();

    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();
    fireEvent.press(screen.getByRole('button', { name: 'See more in Advanced Reports' }));
    expect(navigate).toHaveBeenCalledWith('AdvancedReports');
  });

  it('shows nothing, rather than the locked preview, while the entitlement is still unknown', async () => {
    mockEntitlements.mine.mockReset().mockReturnValue(new Promise(() => {}));
    renderScreen();
    await loadedReport();
    await openTab('Spending');

    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('locked-merchants')).toBeNull();
    expect(screen.queryByTestId('top-merchants')).toBeNull();
  });
});
