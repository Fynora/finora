import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { RefreshControl } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AdvancedReportsScreen } from './AdvancedReportsScreen';
import { analyticsApi, entitlementsApi, reportsApi, type EntitlementsDto } from '../api/endpoints';
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
  analyticsApi: {
    topMerchants: jest.fn(), topCategories: jest.fn(), trend: jest.fn(),
    categoryConfidence: jest.fn(), learningGrowth: jest.fn(),
    multiYearIncome: jest.fn(), multiYearSpend: jest.fn(),
    multiYearCategories: jest.fn(), multiYearLifestyleInflation: jest.fn(),
  },
}));

const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: mockGoBack }),
}));

const entitlements = entitlementsApi as jest.Mocked<typeof entitlementsApi>;
const reports = reportsApi as jest.Mocked<typeof reportsApi>;
const analytics = analyticsApi as jest.Mocked<typeof analyticsApi>;

function granted(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'PREMIUM', planName: 'Premium', features: { ADVANCED_REPORTS: true }, ...overrides };
}

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

beforeEach(() => {
  jest.clearAllMocks();
  reports.availableMonths.mockResolvedValue(['2026-07', '2026-08']);
  analytics.topMerchants.mockResolvedValue([]);
  analytics.topCategories.mockResolvedValue([]);
  analytics.trend.mockResolvedValue([]);
  analytics.categoryConfidence.mockResolvedValue([]);
  analytics.learningGrowth.mockResolvedValue([]);
  analytics.multiYearIncome.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
  analytics.multiYearSpend.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
  analytics.multiYearCategories.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
  analytics.multiYearLifestyleInflation.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
});

describe('AdvancedReportsScreen', () => {
  it('shows an upgrade prompt for a Free user, and fires none of the gated queries', async () => {
    entitlements.mine.mockResolvedValue(granted({ features: {} }));
    renderScreen();

    expect(await screen.findByText(/Advanced Reports is a Plus & Premium feature/)).toBeTruthy();
    expect(analytics.topMerchants).not.toHaveBeenCalled();
    expect(analytics.trend).not.toHaveBeenCalled();
    expect(analytics.categoryConfidence).not.toHaveBeenCalled();
    expect(analytics.learningGrowth).not.toHaveBeenCalled();
  });

  it('names only Plus in the upgrade prompt while Premium is hidden', async () => {
    mockPremium.visible = false;
    try {
      entitlements.mine.mockResolvedValue(granted({ features: {} }));
      renderScreen();

      expect(await screen.findByText(/Advanced Reports is a Plus feature/)).toBeTruthy();
      expect(screen.queryByText(/Premium/)).toBeNull();
    } finally {
      mockPremium.visible = true;
    }
  });

  it('goes back when the back button is pressed', () => {
    entitlements.mine.mockReturnValue(new Promise(() => {}));
    renderScreen();

    fireEvent.press(screen.getByLabelText('Back'));

    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });

  it('renders every panel with its data for an entitled user', async () => {
    entitlements.mine.mockResolvedValue(granted());
    analytics.topMerchants.mockResolvedValue([
      { merchantId: 'm1', merchantName: 'Swiggy', totalSpend: 4500, transactionCount: 12 },
    ]);
    analytics.topCategories.mockResolvedValue([
      { categoryId: 'c1', categoryName: 'Food', totalSpend: 6000, transactionCount: 20 },
    ]);
    analytics.trend.mockResolvedValue([{ month: '2026-08', totalSpend: 15000 }]);
    analytics.categoryConfidence.mockResolvedValue([
      { category: 'Groceries', avgConfidence: 82, merchantCount: 5 },
    ]);
    analytics.learningGrowth.mockResolvedValue([
      { month: '2026-08', learnedCount: 10, correctedCount: 2 },
    ]);

    renderScreen();

    expect(await screen.findByText('Swiggy')).toBeTruthy();
    expect(screen.getByText('Food')).toBeTruthy();
    expect(screen.getByLabelText(/Spend trend over 1 months/)).toBeTruthy();
    expect(screen.getByText('Groceries')).toBeTruthy();
    expect(screen.getByText('82%')).toBeTruthy();
    expect(screen.getByLabelText(/Learning growth over 1 months/)).toBeTruthy();
  });

  it('shows each panel\'s own empty message when there is nothing to show', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();

    expect(await screen.findByText('Import a statement or add transactions to see your top merchants.')).toBeTruthy();
    expect(screen.getByText('Your top spending categories will appear here.')).toBeTruthy();
    expect(screen.getByText('No trend yet.')).toBeTruthy();
    expect(screen.getByText('Confirm a few categorizations and this fills in.')).toBeTruthy();
    expect(screen.getByText('No learning history yet.')).toBeTruthy();
  });

  it('defaults the period to All time', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();

    expect(await screen.findByLabelText('Period: All time')).toBeTruthy();
    expect(analytics.topMerchants).toHaveBeenCalledWith(undefined);
    expect(analytics.topCategories).toHaveBeenCalledWith(undefined);
  });

  it('re-fetches Top Merchants/Categories for the picked month, but not Trend/Confidence/Learning Growth', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();
    await screen.findByLabelText('Period: All time');

    fireEvent.press(screen.getByLabelText('Period: All time'));
    fireEvent.press(await screen.findByTestId('option-August 2026'));

    expect(await screen.findByLabelText('Period: August 2026')).toBeTruthy();
    expect(analytics.topMerchants).toHaveBeenCalledWith('2026-08');
    expect(analytics.topCategories).toHaveBeenCalledWith('2026-08');
    // Only ever called once each -- month-independent, per AnalyticsService's own doc comments.
    expect(analytics.trend).toHaveBeenCalledTimes(1);
    expect(analytics.categoryConfidence).toHaveBeenCalledTimes(1);
    expect(analytics.learningGrowth).toHaveBeenCalledTimes(1);
  });

  it('lists months newest first, with a real formatted label, in the period picker', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();
    await screen.findByLabelText('Period: All time');

    await act(async () => fireEvent.press(screen.getByLabelText('Period: All time')));

    expect(screen.getByTestId('option-All time')).toBeTruthy();
    expect(screen.getByTestId('option-August 2026')).toBeTruthy();
    expect(screen.getByTestId('option-July 2026')).toBeTruthy();
  });

  // Every comparable mobile screen (ReportsScreen, CategoryReviewScreen, GmailReviewScreen) has
  // pull-to-refresh; this pins that AdvancedReportsScreen does too, and that it actually refetches
  // every panel's query -- not just re-renders with whatever was already cached.
  it('refetches every panel, and report-months, on pull-to-refresh', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();
    await screen.findByLabelText('Period: All time');
    jest.clearAllMocks();
    entitlements.mine.mockResolvedValue(granted());
    reports.availableMonths.mockResolvedValue(['2026-07', '2026-08']);
    analytics.topMerchants.mockResolvedValue([]);
    analytics.topCategories.mockResolvedValue([]);
    analytics.trend.mockResolvedValue([]);
    analytics.categoryConfidence.mockResolvedValue([]);
    analytics.learningGrowth.mockResolvedValue([]);

    await act(async () => screen.UNSAFE_getByType(RefreshControl).props.onRefresh());

    expect(reports.availableMonths).toHaveBeenCalledTimes(1);
    expect(analytics.topMerchants).toHaveBeenCalledTimes(1);
    expect(analytics.topCategories).toHaveBeenCalledTimes(1);
    expect(analytics.trend).toHaveBeenCalledTimes(1);
    expect(analytics.categoryConfidence).toHaveBeenCalledTimes(1);
    expect(analytics.learningGrowth).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.UNSAFE_getByType(RefreshControl).props.refreshing).toBe(false));
  });

  it('shows the Multi-Year Comparison section with a coverage badge for a partial year', async () => {
    entitlements.mine.mockResolvedValue(granted());
    analytics.multiYearIncome.mockResolvedValue({
      fullYears: [
        { year: 2025, coverageMonths: 12, isComplete: true, total: 1200000 },
        { year: 2026, coverageMonths: 3, isComplete: false, total: 320000 },
      ],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 180000 }, { year: 2025, total: 160000 }] },
    });
    renderScreen();

    expect(await screen.findByText('Multi-Year Comparison')).toBeTruthy();
    expect(await screen.findByText(/3\/12 months/)).toBeTruthy();
  });

  it('switches to This Year So Far data when that mode is pressed', async () => {
    entitlements.mine.mockResolvedValue(granted());
    analytics.multiYearIncome.mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 1200000 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 180000 }] },
    });
    analytics.multiYearSpend.mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 900000 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 150000 }] },
    });
    renderScreen();

    expect(await screen.findByText('2025 Income')).toBeTruthy();
    expect(screen.queryByText('2026 Income (so far)')).toBeNull();

    fireEvent.press(screen.getByText('This Year So Far'));

    expect(await screen.findByText('2026 Income (so far)')).toBeTruthy();
    expect(screen.queryByText('2025 Income')).toBeNull();
  });

  it('does not render the Lifestyle Inflation card when there is no data for the current mode', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();

    await screen.findByText('Multi-Year Comparison');
    expect(screen.queryByText('Lifestyle Inflation')).toBeNull();
  });

  it('switches the Lifestyle Inflation card to This Year So Far data along with the toggle', async () => {
    entitlements.mine.mockResolvedValue(granted());
    analytics.multiYearIncome.mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 1000000 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 200000 }] },
    });
    analytics.multiYearSpend.mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 800000 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 100000 }] },
    });
    analytics.multiYearLifestyleInflation.mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, income: 1000000, expense: 800000, ratio: 0.8 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, income: 200000, expense: 100000, ratio: 0.5 }] },
    });

    renderScreen();
    await screen.findByText('Lifestyle Inflation');
    // Full Years mode: 2025's ratio (0.8 -> 80%) shows.
    expect(screen.getByText('80%')).toBeTruthy();
    expect(screen.queryByText('50%')).toBeNull();

    fireEvent.press(screen.getByText('This Year So Far'));

    // This Year So Far mode: 2026's ratio (0.5 -> 50%) shows instead, not the stale Full Years one.
    expect(await screen.findByText('50%')).toBeTruthy();
    expect(screen.queryByText('80%')).toBeNull();
  });

  it('shows a coverage qualifier for a partial year in the Lifestyle Inflation card', async () => {
    entitlements.mine.mockResolvedValue(granted());
    analytics.multiYearLifestyleInflation.mockResolvedValue({
      fullYears: [
        { year: 2025, coverageMonths: 12, isComplete: true, income: 1200000, expense: 900000, ratio: 0.75 },
        { year: 2026, coverageMonths: 3, isComplete: false, income: 300000, expense: 270000, ratio: 0.9 },
      ],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderScreen();

    expect(await screen.findByText('Lifestyle Inflation')).toBeTruthy();
    expect(screen.getByText(/3\/12 months/)).toBeTruthy();
  });

  it('shows a dash, not a misleading 0%, for a year with no income to divide by', async () => {
    entitlements.mine.mockResolvedValue(granted());
    analytics.multiYearLifestyleInflation.mockResolvedValue({
      fullYears: [
        { year: 2025, coverageMonths: 12, isComplete: true, income: 0, expense: 5000, ratio: null },
      ],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderScreen();

    expect(await screen.findByText('Lifestyle Inflation')).toBeTruthy();
    expect(screen.getByText('--')).toBeTruthy();
    expect(screen.queryByText('0%')).toBeNull();
  });

  it('shows Category Trends with only the top 5 categories, ranked across every shown year', async () => {
    entitlements.mine.mockResolvedValue(granted());
    // Six categories across two years -- "Misc" is the smallest by cross-year total and must be
    // dropped, proving the ranking sums each category across every shown year rather than just
    // taking whichever five happen to lead in the most recent one.
    analytics.multiYearCategories.mockResolvedValue({
      fullYears: [
        {
          year: 2025, coverageMonths: 12, isComplete: true,
          categories: [
            { categoryId: 'a', categoryName: 'Rent', totalSpend: 240000 },
            { categoryId: 'b', categoryName: 'Groceries', totalSpend: 180000 },
            { categoryId: 'c', categoryName: 'Dining', totalSpend: 90000 },
            { categoryId: 'd', categoryName: 'Shopping', totalSpend: 60000 },
            { categoryId: 'e', categoryName: 'Transport', totalSpend: 40000 },
            { categoryId: 'f', categoryName: 'Misc', totalSpend: 5000 },
          ],
        },
        {
          year: 2026, coverageMonths: 6, isComplete: false,
          categories: [
            { categoryId: 'a', categoryName: 'Rent', totalSpend: 120000 },
            { categoryId: 'b', categoryName: 'Groceries', totalSpend: 90000 },
          ],
        },
      ],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderScreen();

    expect(await screen.findByText('2025 Rent')).toBeTruthy();
    expect(screen.queryByText('2025 Misc')).toBeNull();
    // Groceries is absent from 2026's category list (only Rent/Groceries came through that
    // year) -- still renders a "2026" row for every top-5 name, at ₹0, not a missing row.
    expect(screen.getByText('2026 Rent')).toBeTruthy();
    expect(screen.getByText('2026 Transport')).toBeTruthy();
    expect(screen.queryByText('2026 Misc')).toBeNull();
  });

  it('sums same-named categories within a year instead of dropping all but the first', async () => {
    // Backend labels every deleted category "Uncategorized" regardless of its original name (see
    // AnalyticsService#toBreakdownList's categoryNames.getOrDefault fallback), so one year can
    // legitimately carry two different categoryIds under the identical displayed name. The row
    // must add them together, not silently keep only the first one it finds.
    entitlements.mine.mockResolvedValue(granted());
    analytics.multiYearCategories.mockResolvedValue({
      fullYears: [
        {
          year: 2025, coverageMonths: 12, isComplete: true,
          categories: [
            { categoryId: 'deleted-1', categoryName: 'Uncategorized', totalSpend: 30000 },
            { categoryId: 'deleted-2', categoryName: 'Uncategorized', totalSpend: 15000 },
          ],
        },
      ],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderScreen();

    expect(await screen.findByText('2025 Uncategorized')).toBeTruthy();
    expect(screen.getByText('₹45,000')).toBeTruthy();
  });

  it('shows the Category Trends empty message when there is no multi-year category data', async () => {
    entitlements.mine.mockResolvedValue(granted());
    renderScreen();

    expect(await screen.findByText('Once you have year-over-year category spend, it appears here.')).toBeTruthy();
  });
});
