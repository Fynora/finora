import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { RefreshControl } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AdvancedReportsScreen } from './AdvancedReportsScreen';
import { analyticsApi, entitlementsApi, reportsApi, type EntitlementsDto } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  entitlementsApi: { mine: jest.fn() },
  reportsApi: { availableMonths: jest.fn() },
  analyticsApi: {
    topMerchants: jest.fn(), topCategories: jest.fn(), trend: jest.fn(),
    categoryConfidence: jest.fn(), learningGrowth: jest.fn(),
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
});
