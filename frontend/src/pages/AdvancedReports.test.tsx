import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AdvancedReports from './AdvancedReports';
import { analyticsApi, entitlementsApi, reportsApi } from '../api/endpoints';
import type { EntitlementsDto } from '../api/endpoints';
import { ThemeProvider } from '../context/ThemeContext';

// jsdom has no canvas, so react-chartjs-2's <Line>/<Bar> crash the whole React root on their
// first data update -- same gotcha Dashboard.test.tsx and Investments.test.tsx already document
// and work around. The charts aren't under test here; the entitlement gate and the page's own
// data plumbing are.
vi.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="spend-trend-chart" />,
  // Serializes the datasets actually passed in, so a test can assert on real chart data (which
  // labels/years/values were plotted) rather than only on the surrounding UI -- this page renders
  // three separate <Bar> charts (Multi-Year Comparison, Category Confidence, Learning Growth), so
  // a test must find the right one by its dataset labels rather than assuming a single match.
  Bar: (props: { data: { labels?: string[]; datasets: { label?: string; data: number[] }[] } }) => (
    <div data-testid="bar-chart">{JSON.stringify(props.data)}</div>
  ),
}));

vi.mock('../api/endpoints', () => ({
  entitlementsApi: { mine: vi.fn() },
  analyticsApi: {
    topMerchants: vi.fn(),
    topCategories: vi.fn(),
    trend: vi.fn(),
    categoryConfidence: vi.fn(),
    learningGrowth: vi.fn(),
    multiYearIncome: vi.fn(),
    multiYearSpend: vi.fn(),
    multiYearCategories: vi.fn(),
    multiYearLifestyleInflation: vi.fn(),
  },
  reportsApi: { availableMonths: vi.fn() },
}));

function entitlements(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'FREE', planName: 'Free', features: {}, ...overrides };
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <MemoryRouter>
          <AdvancedReports />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

describe('AdvancedReports', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Never resolving unless a test says otherwise -- a Free-plan test should never need these
    // to resolve at all, since the gate keeps AdvancedReportsContent from ever mounting.
    vi.mocked(analyticsApi.topMerchants).mockReturnValue(pending());
    vi.mocked(analyticsApi.topCategories).mockReturnValue(pending());
    vi.mocked(analyticsApi.trend).mockReturnValue(pending());
    vi.mocked(analyticsApi.categoryConfidence).mockReturnValue(pending());
    vi.mocked(analyticsApi.learningGrowth).mockReturnValue(pending());
    vi.mocked(analyticsApi.multiYearIncome).mockReturnValue(pending());
    vi.mocked(analyticsApi.multiYearSpend).mockReturnValue(pending());
    vi.mocked(analyticsApi.multiYearCategories).mockReturnValue(pending());
    vi.mocked(analyticsApi.multiYearLifestyleInflation).mockReturnValue(pending());
    vi.mocked(reportsApi.availableMonths).mockReturnValue(pending());
  });

  it('always shows the page title, even before the entitlement check resolves', () => {
    vi.mocked(entitlementsApi.mine).mockReturnValue(pending());

    renderPage();

    expect(screen.getByRole('heading', { name: /Advanced Reports/ })).toBeInTheDocument();
  });

  it('shows the upgrade prompt, not the report content, for a Free-plan user', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ features: {} }));

    renderPage();

    expect(await screen.findByText(/Plus & Premium feature/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View plans/ })).toHaveAttribute('href', '/app/billing');
    // The gated content never mounts for a denied user -- none of its queries should ever fire.
    expect(analyticsApi.topMerchants).not.toHaveBeenCalled();
    expect(screen.queryByText('Top Merchants')).not.toBeInTheDocument();
  });

  it('shows the upgrade prompt for a user with no subscription at all (fails closed)', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue({ planCode: null, planName: null, features: {} });

    renderPage();

    expect(await screen.findByText(/Plus & Premium feature/)).toBeInTheDocument();
  });

  it('renders real report data for an entitled (Plus/Premium) user', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PLUS', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([
      { merchantId: 'm1', merchantName: 'Swiggy', totalSpend: 4500, transactionCount: 12 },
    ]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([
      { categoryId: 'c1', categoryName: 'Food', totalSpend: 6000, transactionCount: 15 },
    ]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([{ month: '2026-08', totalSpend: 12000 }]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([{ category: 'Food', avgConfidence: 88, merchantCount: 4 }]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([{ month: '2026-08', learnedCount: 10, correctedCount: 2 }]);

    renderPage();

    expect(await screen.findByText('Swiggy')).toBeInTheDocument();
    expect(screen.getByText('Food')).toBeInTheDocument();
    expect(screen.queryByText(/Plus & Premium feature/)).not.toBeInTheDocument();
  });

  it('shows an empty state per section when an entitled user has no data yet', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PREMIUM', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue([]);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);

    renderPage();

    expect(await screen.findByText('No merchant spend yet')).toBeInTheDocument();
    expect(screen.getByText('No categorized spend yet')).toBeInTheDocument();
  });

  it('shows the Multi-Year Comparison section with a real, visible coverage badge for a partial year', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PLUS', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);
    vi.mocked(analyticsApi.multiYearIncome).mockResolvedValue({
      fullYears: [
        { year: 2025, coverageMonths: 12, isComplete: true, total: 1200000 },
        { year: 2026, coverageMonths: 3, isComplete: false, total: 320000 },
      ],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 180000 }, { year: 2025, total: 160000 }] },
    });
    vi.mocked(analyticsApi.multiYearSpend).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 900000 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [] },
    });
    vi.mocked(analyticsApi.multiYearLifestyleInflation).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, income: 1200000, expense: 900000, ratio: 0.75 }],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });
    vi.mocked(analyticsApi.multiYearCategories).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, categories: [] }],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderPage();

    expect(await screen.findByText('Multi-Year Comparison')).toBeInTheDocument();
    // The <li> renders "2026: 3/12 months" as one combined text node (year, then the literal ": ",
    // then the conditional coverage string are adjacent JSX children with no intervening element) --
    // a regex partial match, not an exact string, is what actually matches that combined content.
    expect(await screen.findByText(/3\/12 months/)).toBeInTheDocument();
  });

  it('shows the empty state, not a blank chart, when This Year So Far has no comparable years', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PLUS', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue([]);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);
    // fullYears is non-empty (so the "Full Years" mode has real data), but thisYearSoFar.years is
    // empty -- e.g. no prior year fully covers the current window yet. Switching modes must not
    // read isEmpty from the wrong dataset and render a blank chart instead of the empty state.
    vi.mocked(analyticsApi.multiYearIncome).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 1200000 }],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });
    vi.mocked(analyticsApi.multiYearSpend).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 900000 }],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });
    vi.mocked(analyticsApi.multiYearLifestyleInflation).mockResolvedValue({
      fullYears: [],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });
    vi.mocked(analyticsApi.multiYearCategories).mockResolvedValue({
      fullYears: [],
      thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderPage();
    await screen.findByText('Multi-Year Comparison');
    // "Full Years" mode has real data -- the empty state must not show yet.
    expect(screen.queryByText('Not enough history yet')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'This Year So Far' }));

    expect(await screen.findByText('Not enough history yet')).toBeInTheDocument();
  });

  it('switches the Lifestyle Inflation card to This Year So Far data along with the toggle', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PLUS', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue([]);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);
    vi.mocked(analyticsApi.multiYearIncome).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 1000000 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 200000 }] },
    });
    vi.mocked(analyticsApi.multiYearSpend).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 800000 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 100000 }] },
    });
    vi.mocked(analyticsApi.multiYearLifestyleInflation).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, income: 1000000, expense: 800000, ratio: 0.8 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, income: 200000, expense: 100000, ratio: 0.5 }] },
    });
    vi.mocked(analyticsApi.multiYearCategories).mockResolvedValue({
      fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderPage();
    await screen.findByText('Lifestyle Inflation');
    // Full Years mode: 2025's ratio (0.8 -> 80%) shows.
    expect(screen.getByText('80%')).toBeInTheDocument();
    expect(screen.queryByText('50%')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'This Year So Far' }));

    // This Year So Far mode: 2026's ratio (0.5 -> 50%) shows instead, not the stale Full Years one.
    expect(await screen.findByText('50%')).toBeInTheDocument();
    expect(screen.queryByText('80%')).not.toBeInTheDocument();
  });

  it('actually swaps the chart data, not just the surrounding UI, when the mode toggle is pressed', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PLUS', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue([]);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);
    vi.mocked(analyticsApi.multiYearIncome).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 1111111 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 2222222 }] },
    });
    vi.mocked(analyticsApi.multiYearSpend).mockResolvedValue({
      fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 3333333 }],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 4444444 }] },
    });
    vi.mocked(analyticsApi.multiYearLifestyleInflation).mockResolvedValue({
      fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] },
    });
    vi.mocked(analyticsApi.multiYearCategories).mockResolvedValue({
      fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] },
    });

    renderPage();
    await screen.findByText('Multi-Year Comparison');

    // Three <Bar> charts render on this page -- only this one's datasets are ever labeled
    // "Income" (Category Confidence uses "Avg. confidence", Learning Growth uses
    // "Learned"/"Corrected"), so filtering on that finds this specific chart unambiguously.
    // Each chart's own query resolves independently and asynchronously, so this must poll
    // (waitFor) rather than read the DOM once immediately after the section title appears.
    async function multiYearChartJson() {
      let match: HTMLElement | undefined;
      await waitFor(() => {
        match = screen.getAllByTestId('bar-chart').find((el) => el.textContent?.includes('"Income"'));
        expect(match).toBeDefined();
      });
      return JSON.parse(match!.textContent!) as { labels: string[]; datasets: { label: string; data: number[] }[] };
    }

    const fullYearsChart = await multiYearChartJson();
    expect(fullYearsChart.labels).toEqual(['2025']);
    expect(fullYearsChart.datasets.find((d) => d.label === 'Income')?.data).toEqual([1111111]);
    expect(fullYearsChart.datasets.find((d) => d.label === 'Spend')?.data).toEqual([3333333]);

    fireEvent.click(screen.getByRole('button', { name: 'This Year So Far' }));

    const ytdChart = await multiYearChartJson();
    expect(ytdChart.labels).toEqual(['2026']);
    expect(ytdChart.datasets.find((d) => d.label === 'Income')?.data).toEqual([2222222]);
    expect(ytdChart.datasets.find((d) => d.label === 'Spend')?.data).toEqual([4444444]);
  });
});
