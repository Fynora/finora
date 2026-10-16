import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Reports from './Reports';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { analyticsApi, categoriesApi, entitlementsApi, insightsApi, reportsApi, type InsightsData, type ReportData } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  reportsApi: { availableMonths: vi.fn(), forMonth: vi.fn(), trend: vi.fn() },
  categoriesApi: { list: vi.fn() },
  insightsApi: { get: vi.fn() },
  entitlementsApi: { mine: vi.fn() },
  analyticsApi: { topMerchants: vi.fn() },
}));

// The extras (categories, insights, trend) are optional on the page, so every test starts with
// "none of them", and the ones about them say otherwise. A real default, not an unset mock: an
// unset one returns undefined and would throw inside the hook's .then().
beforeEach(() => {
  vi.mocked(categoriesApi.list).mockResolvedValue([]);
  vi.mocked(insightsApi.get).mockResolvedValue({ sentences: [], movers: [] });
  vi.mocked(reportsApi.trend).mockResolvedValue([]);
  // No Advanced Reports unless a test says otherwise, so the paid endpoint is never called by default.
  vi.mocked(entitlementsApi.mine).mockResolvedValue({ features: { ADVANCED_REPORTS: false } } as never);
  vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
});

function report(overrides: Partial<ReportData> = {}): ReportData {
  return {
    month: '2026-08',
    income: 100000,
    expense: 40000,
    categories: [{ category: 'Food', amount: 25000 }],
    ...overrides,
  };
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Reports />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Reports — loading states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('announces the initial load immediately instead of rendering plain "Loading…" text', () => {
    vi.mocked(reportsApi.availableMonths).mockReturnValue(pending<string[]>());
    vi.mocked(reportsApi.forMonth).mockReturnValue(pending<ReportData>());

    renderPage();

    const region = screen.getByRole('status');
    expect(region).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading your reports')).toBeInTheDocument();
  });

  /**
   * The window the roadmap missed entirely. `loading` only ever covered availableMonths(); once
   * months resolved, the toolbar rendered and the `{report && …}` guard rendered NOTHING beneath it
   * for the whole first forMonth() call -- no figures, no skeleton, no text.
   */
  it('shows a loading region below the toolbar during the first month fetch, not a blank page', async () => {
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(reportsApi.forMonth).mockReturnValue(pending<ReportData>());

    renderPage();

    // Toolbar is up (months resolved)...
    expect(await screen.findByLabelText('Month')).toBeInTheDocument();
    // ...and the body below it announces itself rather than being empty, naming the actual month
    // on screen rather than asserting "this month" -- see check-reporting-period-labels.py.
    expect(screen.getByText("Loading August 2026's report")).toBeInTheDocument();
  });

  /**
   * The headline gap. Switching month must keep the previous month's figures on screen with a
   * spinner -- NOT swap them for a skeleton, which is what feeding the raw loading flag into
   * useDelayedLoading would have done (its own doc comment forbids exactly that).
   */
  it('keeps the previous month visible with a Refreshing indicator while a new month loads', async () => {
    const user = userEvent.setup();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValueOnce(report({ month: '2026-08', expense: 40000 }));

    renderPage();
    expect(await screen.findByText('Food')).toBeInTheDocument();

    // Second month never resolves, so we can inspect the in-flight state.
    vi.mocked(reportsApi.forMonth).mockReturnValueOnce(pending<ReportData>());
    await user.selectOptions(screen.getByLabelText('Month'), '2026-07');

    expect(await screen.findByText('Refreshing…')).toBeInTheDocument();
    // The previous month's data is still there -- not replaced by a skeleton.
    expect(screen.getByText('Food')).toBeInTheDocument();
    expect(screen.queryByText("Loading July 2026's report")).not.toBeInTheDocument();
  });

  /**
   * The useAsyncGuard clear. `setReportLoading(false)` sits inside `if (isCurrent())` so a
   * superseded response cannot switch the indicator off while the request whose data is actually
   * awaited is still running. Nothing else in this file has two overlapping forMonth calls that
   * BOTH resolve, so without this the guard could be deleted with the suite still green.
   */
  it('ignores a superseded month response rather than overwriting or clearing the indicator', async () => {
    const user = userEvent.setup();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    const deferred: Array<(r: ReportData) => void> = [];
    vi.mocked(reportsApi.forMonth).mockImplementation(
      () => new Promise<ReportData>((resolve) => { deferred.push(resolve); })
    );

    renderPage();

    await waitFor(() => expect(deferred).toHaveLength(1));
    deferred[0](report({ month: '2026-08', categories: [{ category: 'Food', amount: 4100 }] }));
    expect(await screen.findByText('Food')).toBeInTheDocument();

    // Away and straight back: request #1 (July) is abandoned, #2 (August) is the live one.
    await user.selectOptions(screen.getByLabelText('Month'), '2026-07');
    await user.selectOptions(screen.getByLabelText('Month'), '2026-08');
    await waitFor(() => expect(deferred).toHaveLength(3));
    expect(await screen.findByText('Refreshing…')).toBeInTheDocument();

    // The abandoned July response lands FIRST.
    deferred[1](report({ month: '2026-07', categories: [{ category: 'Transport', amount: 2100 }] }));
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByText('Transport')).not.toBeInTheDocument();
    expect(screen.getByText('Refreshing…')).toBeInTheDocument();

    // ...and only the live one settles the page.
    deferred[2](report({ month: '2026-08', categories: [{ category: 'Dining', amount: 1800 }] }));
    await waitFor(() => expect(screen.queryByText('Refreshing…')).not.toBeInTheDocument());
    expect(screen.getByText('Dining')).toBeInTheDocument();
  });

  it('disables Export CSV while a month switch is in flight, so it cannot export the month just left', async () => {
    const user = userEvent.setup();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValueOnce(report({ month: '2026-08' }));

    renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /export csv/i })).toBeEnabled());

    vi.mocked(reportsApi.forMonth).mockReturnValueOnce(pending<ReportData>());
    await user.selectOptions(screen.getByLabelText('Month'), '2026-07');

    await screen.findByText('Refreshing…');
    expect(screen.getByRole('button', { name: /export csv/i })).toBeDisabled();
  });

  it('disables Export CSV until a report has actually loaded', async () => {
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReturnValue(pending<ReportData>());

    renderPage();

    expect(await screen.findByRole('button', { name: /export csv/i })).toBeDisabled();
  });

  it('enables Export CSV once the report is in', async () => {
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());

    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: /export csv/i })).toBeEnabled());
  });
});

describe('Reports — money not counted as income', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
  });

  it('says how much came in but is not counted once, in one review notice', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ unresolvedInflow: 84500, unresolvedInflowCount: 3 }));
    renderPage();

    expect(await screen.findByTestId('review-notice')).toHaveTextContent("3 credits worth ₹84,500 aren't counted as income yet.");
    // Not repeated in the hero: one place to read it, one button to act on it.
    expect(screen.getAllByText(/not counted as income|counted as income yet/)).toHaveLength(1);
  });

  it('reads whole in the singular, and without a count from an older server', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ unresolvedInflow: 700, unresolvedInflowCount: 1 }));
    const { unmount } = renderPage();
    expect(await screen.findByTestId('review-notice')).toHaveTextContent("1 credit worth ₹700 isn't counted as income yet.");
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ unresolvedInflow: 700 }));
    renderPage();
    expect(await screen.findByTestId('review-notice')).toHaveTextContent("₹700 received isn't counted as income yet.");
  });

  it('lists income by kind and links to the review page for the month', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      unresolvedInflow: 700,
      incomeByKind: [{ label: 'Salary', amount: 90000 }, { label: 'Family support', amount: 10000 }],
    }));
    renderPage();

    expect(await screen.findByText('Income by kind')).toBeInTheDocument();
    expect(screen.getByText('Family support')).toBeInTheDocument();
    expect(screen.getByText('₹10,000')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review money' }))
      .toHaveAttribute('href', '/app/money-review?start=2026-08-01&end=2026-08-31');
  });

  it('shows no income-by-kind list for a single line', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ incomeByKind: [{ label: 'Salary', amount: 100000 }] }));
    renderPage();
    await screen.findByText('₹1,00,000');
    expect(screen.queryByText('Income by kind')).not.toBeInTheDocument();
  });

  it('says nothing when everything that came in is counted, or the server does not send the field', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ unresolvedInflow: 0 }));
    const { unmount } = renderPage();
    await screen.findByText('₹1,00,000');
    expect(screen.queryByText(/not counted as income/)).not.toBeInTheDocument();
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());
    renderPage();
    await screen.findByText('₹1,00,000');
    expect(screen.queryByText(/not counted as income/)).not.toBeInTheDocument();
  });
});

const comparison = (over: Partial<NonNullable<ReportData['comparison']>> = {}): NonNullable<ReportData['comparison']> => ({
  previousMonth: '2026-07', reliable: true, gateReason: null, previousIncome: 90000, previousExpense: 70000,
  incomeDeltaPct: 11.1, expenseDeltaPct: -42.9, netDelta: 40000, ...over,
});

describe('Reports — the hero', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
  });

  it('says what share of income was kept, shows the net as a signed amount and draws the ring and the bar', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ savingsRatePct: 60, savingsRateGateReason: null }));
    renderPage();

    expect(await screen.findByTestId('hero-headline')).toHaveTextContent('You kept 60% of what you earned.');
    expect(screen.getByTestId('hero-net')).toHaveTextContent('+₹60,000');
    expect(screen.getByTestId('hero-ring')).toHaveAttribute('aria-label', 'Savings rate 60%');
    expect(screen.getByRole('img', { name: 'Of ₹1,00,000 earned, ₹40,000 spent and ₹60,000 kept' })).toBeInTheDocument();
  });

  it('says so in words when spending outran income, and draws no bar for it', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ income: 1000, expense: 1500, categories: [], savingsRatePct: -50 }));
    renderPage();

    expect(await screen.findByTestId('hero-headline')).toHaveTextContent('You spent 50% more than you earned.');
    expect(screen.getByTestId('hero-net')).toHaveTextContent('-₹500');
    expect(screen.queryByTestId('hero-bar')).not.toBeInTheDocument();
  });

  it('keeps a thousands-of-percent rate inside the ring by drawing its figure smaller', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ income: 500, expense: 6000, categories: [], savingsRatePct: -1100 }));
    renderPage();

    const ring = await screen.findByTestId('hero-ring');
    expect(ring).toHaveAttribute('aria-label', 'Savings rate -1100%');
    expect(ring.querySelector('text')).toHaveAttribute('font-size', '12');
    expect(ring.querySelector('text')).toHaveTextContent('-1100%');
  });

  it('draws an ordinary rate at full size', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ savingsRatePct: 60 }));
    renderPage();
    expect((await screen.findByTestId('hero-ring')).querySelector('text')).toHaveAttribute('font-size', '20');
  });

  it('never shows a signed zero for a net under half a rupee', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ income: 1000, expense: 1000.4, categories: [], savingsRatePct: -0.04 }));
    renderPage();
    expect(await screen.findByTestId('hero-net')).toHaveTextContent(/^₹0$/);
  });

  it('says it broke even rather than "kept 0%" or "spent 0% more"', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ income: 1000, expense: 998, savingsRatePct: 0.2 }));
    renderPage();
    expect(await screen.findByTestId('hero-headline')).toHaveTextContent('You broke even this month.');
  });

  it('explains why there is no rate, instead of showing a figure unclassified money would decide', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      income: 500, expense: 100, savingsRatePct: null, savingsRateGateReason: 'UNRESOLVED_EXCEEDS_INCOME', unresolvedInflow: 3000,
    }));
    const { unmount } = renderPage();
    expect(await screen.findByTestId('hero-headline'))
      .toHaveTextContent("Money you received hasn't been sorted into income yet, so there is no savings rate.");
    expect(screen.queryByTestId('hero-ring')).not.toBeInTheDocument();
    // Nor the kept-vs-spent bar: it is the same doubtful share, drawn.
    expect(screen.queryByTestId('hero-bar')).not.toBeInTheDocument();
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ income: 0, expense: 800, savingsRatePct: null, savingsRateGateReason: 'NO_INCOME' }));
    renderPage();
    expect(await screen.findByTestId('hero-headline')).toHaveTextContent('No income was counted this month.');
    expect(screen.getByTestId('hero-net')).toHaveTextContent('-₹800');
  });

  it('says nothing about a rate from an older server, and still shows the net', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());
    renderPage();

    expect(await screen.findByTestId('hero-net')).toHaveTextContent('+₹60,000');
    expect(screen.queryByTestId('hero-headline')).not.toBeInTheDocument();
    expect(screen.queryByTestId('hero-ring')).not.toBeInTheDocument();
  });

  it('says nothing for a month with no activity at all', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      income: 0, expense: 0, categories: [], savingsRatePct: null, savingsRateGateReason: 'NO_INCOME',
    }));
    renderPage();
    expect(await screen.findByTestId('hero-net')).toHaveTextContent('₹0');
    expect(screen.queryByTestId('hero-headline')).not.toBeInTheDocument();
  });

  it("states the net's change as an amount against the month before, never a percent", async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ savingsRatePct: 60, comparison: comparison() }));
    renderPage();
    expect(await screen.findByTestId('hero-compare')).toHaveTextContent('₹40,000 more than July.');
  });

  it("explains in one quiet line why there is no comparison, naming the months", async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      savingsRatePct: 60,
      comparison: comparison({ reliable: false, gateReason: 'PARTIAL_PRIOR_MONTH', netDelta: null, incomeDeltaPct: null, expenseDeltaPct: null }),
    }));
    renderPage();
    expect(await screen.findByTestId('hero-compare-note')).toHaveTextContent('July is only partly in your history, so it is not compared with August.');
    expect(screen.queryByTestId('hero-compare')).not.toBeInTheDocument();
  });

  it('shows no comparison line at all for a user with no live account', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ savingsRatePct: 60, comparison: null }));
    renderPage();
    await screen.findByTestId('hero-net');
    expect(screen.queryByTestId('hero-compare')).not.toBeInTheDocument();
    expect(screen.queryByTestId('hero-compare-note')).not.toBeInTheDocument();
  });

  it('does not say a partly-in month twice: the comparison note already gives the reason', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      complete: false,
      comparison: comparison({ reliable: false, gateReason: 'SELECTED_MONTH_PARTIAL', netDelta: null, incomeDeltaPct: null, expenseDeltaPct: null }),
    }));
    renderPage();

    expect(await screen.findByTestId('hero-compare-note')).toHaveTextContent('August is only partly in your history');
    expect(screen.queryByTestId('hero-partial')).not.toBeInTheDocument();
  });

  it('says a month that is only partly in may change, and only then', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ complete: false }));
    const { unmount } = renderPage();
    expect(await screen.findByTestId('hero-partial')).toHaveTextContent("Part of this month isn't in yet");
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ complete: true }));
    renderPage();
    await screen.findByTestId('hero-net');
    expect(screen.queryByTestId('hero-partial')).not.toBeInTheDocument();
  });
});

describe('Reports — income and expense cards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
  });

  it("show how each moved since the month before, and the fall in expenses reads as good", async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ comparison: comparison() }));
    renderPage();

    expect(await screen.findByText('▲ 11.1%')).toHaveClass('text-success');
    const fall = screen.getByText('▼ 42.9%');
    expect(fall).toHaveClass('text-success');
    expect(screen.getAllByText('vs July')).toHaveLength(2);
  });

  it('shows a rise in expenses as bad', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ comparison: comparison({ expenseDeltaPct: 30 }) }));
    renderPage();
    expect(await screen.findByText('▲ 30.0%')).toHaveClass('text-danger');
  });

  it('leaves the delta line off when the comparison is not trusted, and the hero says why once', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      comparison: comparison({ reliable: false, gateReason: 'TOO_FEW_PRIOR_TRANSACTIONS', netDelta: null, incomeDeltaPct: null, expenseDeltaPct: null }),
    }));
    renderPage();

    expect(await screen.findByTestId('hero-compare-note')).toHaveTextContent('July has too few transactions to compare August with.');
    expect(screen.queryByText(/▲|▼/)).not.toBeInTheDocument();
    expect(screen.queryByText(/vs July/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Why?' })).not.toBeInTheDocument();
  });

  it('never shows a figure the server marked unreliable, even if it still sent one', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      comparison: comparison({ reliable: false, gateReason: 'COVERAGE_GAP', incomeDeltaPct: 900, expenseDeltaPct: -90, netDelta: 123 }),
    }));
    renderPage();

    await screen.findByText('Total income');
    expect(screen.queryByText(/▲|▼/)).not.toBeInTheDocument();
    expect(screen.queryByText(/vs July/)).not.toBeInTheDocument();
    expect(screen.queryByTestId('hero-compare')).not.toBeInTheDocument();
  });

  it('shows no comparison line for a user with no live account', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ comparison: null }));
    renderPage();
    await screen.findByText('Total income');
    expect(screen.queryByText(/^(—\s)?vs /)).not.toBeInTheDocument();
  });
});

const cat = (name: string, over: Record<string, unknown> = {}) =>
  ({ id: `id-${name}`, name, isSystem: false, icon: 'tag', color: 'blue', ...over });
const mover = (category: string, pctChange: number | null, current = 100, priorAverage = 80) =>
  ({ category, current, priorAverage, pctChange });
const insights = (over: Partial<InsightsData> = {}): InsightsData => ({
  sentences: [], movers: [], reportingMonth: '2026-08', reportingMonthIsCurrent: false, ...over,
});

describe('Reports — where your money went', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
  });

  it('divides shares by the rows own total, so none can pass 100% when Expense is smaller than the rows', async () => {
    // Expense leaves out an investment transfer that is still a category row.
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      expense: 500, categories: [{ category: 'Rent', amount: 500 }, { category: 'Investments', amount: 500 }],
    }));
    renderPage();

    const rows = await screen.findByTestId('category-rows');
    expect(within(rows).getAllByText('50%')).toHaveLength(2);
    expect(within(rows).queryByText(/1\d\d%/)).not.toBeInTheDocument();
    expect(screen.getByTestId('category-donut')).toHaveAttribute('aria-label', expect.stringContaining('₹1,000 in total'));
  });

  it('closes the ring without a notch when there is one category, and keeps a hairline between several', async () => {
    const circumference = 2 * Math.PI * 38;
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ categories: [{ category: 'Rent', amount: 500 }] }));
    const { unmount } = renderPage();
    const single = (await screen.findByTestId('category-donut')).querySelector('circle')!;
    expect(parseFloat(single.getAttribute('stroke-dasharray')!.split(' ')[0])).toBeCloseTo(circumference, 2);
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      categories: [{ category: 'Rent', amount: 500 }, { category: 'Food', amount: 500 }],
    }));
    renderPage();
    const first = (await screen.findByTestId('category-donut')).querySelector('circle')!;
    expect(parseFloat(first.getAttribute('stroke-dasharray')!.split(' ')[0])).toBeCloseTo(circumference / 2 - 1.5, 2);
  });

  it('opens the ledger for a real category and month, and is not a link for one the ledger cannot filter to', async () => {
    vi.mocked(categoriesApi.list).mockResolvedValue([cat('Food & Dining')] as never);
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      categories: [{ category: 'Food & Dining', amount: 600 }, { category: 'Uncategorized', amount: 400 }],
    }));
    renderPage();

    const link = await screen.findByRole('link', { name: /Food & Dining: ₹600/ });
    const href = link.getAttribute('href')!;
    expect(href.startsWith('/app/transactions?')).toBe(true);
    const params = new URLSearchParams(href.split('?')[1]);
    expect(params.get('category')).toBe('Food & Dining');
    expect(params.get('from')).toBe('2026-08-01');
    expect(params.get('to')).toBe('2026-08-31');
    // Uncategorized shows its amount but is plain text: it also holds rows in deleted categories.
    expect(screen.getByText('Uncategorized')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Uncategorized/ })).not.toBeInTheDocument();
  });

  it("shows each category's change against the recent average, with spending up as the bad direction", async () => {
    vi.mocked(categoriesApi.list).mockResolvedValue([cat('Shopping'), cat('Food')] as never);
    vi.mocked(insightsApi.get).mockResolvedValue(insights({ movers: [mover('Shopping', -25.4), mover('Food', 12.2)] }));
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      categories: [{ category: 'Shopping', amount: 600 }, { category: 'Food', amount: 400 }],
    }));
    renderPage();

    const chips = await screen.findAllByTestId('category-change');
    expect(chips.map((c) => c.textContent)).toEqual(['▼ 25%', '▲ 12%']);
    expect(chips[0]).toHaveClass('text-success');
    expect(chips[1]).toHaveClass('text-danger');
  });

  it('shows no chip for a category with no history, and none at all when insights are unavailable', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights({ movers: [mover('Shopping', null)] }));
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ categories: [{ category: 'Shopping', amount: 600 }] }));
    const { unmount } = renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('category-change')).not.toBeInTheDocument();
    unmount();

    vi.mocked(insightsApi.get).mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByTestId('category-rows')).toBeInTheDocument();
    expect(screen.queryByTestId('category-change')).not.toBeInTheDocument();
  });

  it('ignores insights that describe a different month than the one on screen', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights({ reportingMonth: '2026-07', movers: [mover('Food', 40)] }));
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ categories: [{ category: 'Food', amount: 400 }] }));
    renderPage();

    await screen.findByTestId('category-rows');
    await waitFor(() => expect(insightsApi.get).toHaveBeenCalledWith('2026-08'));
    expect(screen.queryByTestId('category-change')).not.toBeInTheDocument();
  });

  it('keeps the page whole when the categories or the insights fail to load', async () => {
    vi.mocked(categoriesApi.list).mockRejectedValue(new Error('boom'));
    vi.mocked(insightsApi.get).mockRejectedValue(new Error('boom'));
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ categories: [{ category: 'Food', amount: 400 }] }));
    renderPage();

    expect(await screen.findByTestId('category-rows')).toBeInTheDocument();
    expect(screen.getByText('Food')).toBeInTheDocument();
  });

  it('lists the top eight and opens the rest on request', async () => {
    const user = userEvent.setup();
    const many = Array.from({ length: 11 }, (_, i) => ({ category: `Cat ${i + 1}`, amount: 1000 - i * 10 }));
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ categories: many }));
    renderPage();

    const rows = await screen.findByTestId('category-rows');
    expect(within(rows).getAllByRole('listitem')).toHaveLength(8);
    await user.click(screen.getByRole('button', { name: 'Show all 11 categories' }));
    expect(within(rows).getAllByRole('listitem')).toHaveLength(11);
    await user.click(screen.getByRole('button', { name: 'Show fewer' }));
    expect(within(rows).getAllByRole('listitem')).toHaveLength(8);
  });

  it('never shows the new month\'s insights against the previous month\'s report while a switch is in flight', async () => {
    const user = userEvent.setup();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(insightsApi.get).mockImplementation(async (m?: string) =>
      m === '2026-07'
        ? insights({ reportingMonth: '2026-07', movers: [mover('Food', -10)] })
        : insights({ reportingMonth: '2026-08', movers: [mover('Food', 40)] }));
    vi.mocked(categoriesApi.list).mockResolvedValue([cat('Food')] as never);
    vi.mocked(reportsApi.forMonth).mockImplementation((m: string) =>
      m === '2026-08' ? Promise.resolve(report({ categories: [{ category: 'Food', amount: 400 }] })) : pending<ReportData>());
    renderPage();

    expect((await screen.findAllByTestId('category-change')).map((c) => c.textContent)).toEqual(['▲ 40%']);
    await user.selectOptions(screen.getByLabelText('Month'), '2026-07');
    await waitFor(() => expect(insightsApi.get).toHaveBeenCalledWith('2026-07'));

    // August's report is still on screen, July's report has not arrived: July's chip must not appear.
    expect(screen.queryByText('▼ 10%')).not.toBeInTheDocument();
    expect(within(screen.getByTestId('category-rows')).getByText('₹400')).toBeInTheDocument();
  });

  it('offers no "show all" for eight or fewer, and says so plainly for a month with no spending', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ categories: [{ category: 'Food', amount: 400 }] }));
    const { unmount } = renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByRole('button', { name: /Show all/ })).not.toBeInTheDocument();
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ categories: [] }));
    renderPage();
    expect(await screen.findByText('No expenses recorded')).toBeInTheDocument();
    expect(screen.queryByTestId('category-donut')).not.toBeInTheDocument();
  });
});

describe('Reports — the month switcher', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-06', '2026-07', '2026-08']);
    vi.mocked(reportsApi.forMonth).mockImplementation(async (m: string) => report({ month: m }));
  });

  it('names months the way people say them, and starts on the newest', async () => {
    renderPage();
    const picker = await screen.findByLabelText('Month');
    expect(picker).toHaveValue('2026-08');
    expect(within(picker).getAllByRole('option').map((o) => o.textContent)).toEqual(['June 2026', 'July 2026', 'August 2026']);
  });

  it('steps back and forward with the arrows, asking the server for each month', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByLabelText('Month');

    await user.click(screen.getByRole('button', { name: 'Previous month' }));
    await waitFor(() => expect(reportsApi.forMonth).toHaveBeenCalledWith('2026-07'));
    expect(screen.getByLabelText('Month')).toHaveValue('2026-07');

    await user.click(screen.getByRole('button', { name: 'Next month' }));
    await waitFor(() => expect(screen.getByLabelText('Month')).toHaveValue('2026-08'));
  });

  it('disables the arrows at either end instead of wrapping', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByLabelText('Month');
    expect(screen.getByRole('button', { name: 'Next month' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Previous month' })).toBeEnabled();

    await user.selectOptions(screen.getByLabelText('Month'), '2026-06');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Previous month' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Next month' })).toBeEnabled();
  });

  it('has both arrows disabled for a single month', async () => {
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    renderPage();
    await screen.findByLabelText('Month');
    expect(screen.getByRole('button', { name: 'Previous month' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next month' })).toBeDisabled();
  });
});

describe('Reports — worth noticing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());
  });

  it('leads with the biggest move and lists the rest beneath it', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights({ movers: [mover('Food', 20, 1200, 1000), mover('Shopping', -40, 600, 1000)] }));
    renderPage();

    expect(await screen.findByTestId('observation-lead')).toHaveTextContent('Shopping is 40% below your recent average (₹600 against ₹1,000).');
    expect(screen.getByTestId('observation-rest')).toHaveTextContent('Food is 20% above your recent average');
  });

  it('is not drawn at all when there is nothing to say, or when the insights fail', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights({ movers: [mover('Food', 3)] }));
    const { unmount } = renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Worth noticing')).not.toBeInTheDocument();
    unmount();

    vi.mocked(insightsApi.get).mockRejectedValue(new Error('boom'));
    renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Worth noticing')).not.toBeInTheDocument();
  });
});

describe('Reports — the last months', () => {
  const point = (month: string, income: number, expense: number, complete = true) =>
    ({ month, income, expense, transactionCount: 10, complete });

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());
  });

  it('draws income and expenses for each month, with the average of the whole months', async () => {
    vi.mocked(reportsApi.trend).mockResolvedValue([point('2026-06', 1000, 400), point('2026-07', 1000, 600), point('2026-08', 1000, 500)]);
    renderPage();

    const chart = await screen.findByTestId('trend-chart');
    expect(chart).toHaveAttribute('aria-label', expect.stringContaining('Jun: income ₹1,000, expenses ₹400'));
    expect(screen.getByTestId('trend-average-note')).toHaveTextContent('average spend, ₹500');
    expect(screen.queryByTestId('trend-partial-note')).not.toBeInTheDocument();
    expect(reportsApi.trend).toHaveBeenCalledWith('2026-08', 6);
  });

  it('marks a partly-in month, says so, and leaves it out of the average', async () => {
    vi.mocked(reportsApi.trend).mockResolvedValue([point('2026-06', 1000, 400), point('2026-07', 1000, 600), point('2026-08', 1000, 50, false)]);
    renderPage();

    expect(await screen.findByTestId('trend-partial-note')).toHaveTextContent('Only part of the month is in');
    expect(screen.getByTestId('trend-chart')).toHaveAttribute('aria-label', expect.stringContaining('Aug (only part of the month is in)'));
    expect(screen.getByTestId('trend-average-note')).toHaveTextContent('₹500');
  });

  it('draws no average line from fewer than two whole months', async () => {
    vi.mocked(reportsApi.trend).mockResolvedValue([point('2026-07', 1000, 600), point('2026-08', 1000, 50, false)]);
    renderPage();

    await screen.findByTestId('trend-chart');
    expect(screen.queryByTestId('trend-average')).not.toBeInTheDocument();
  });

  it('is not drawn for a single month, for none, or when it fails to load', async () => {
    vi.mocked(reportsApi.trend).mockResolvedValue([point('2026-08', 1000, 500)]);
    const first = renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('trend-chart')).not.toBeInTheDocument();
    first.unmount();

    vi.mocked(reportsApi.trend).mockRejectedValue(new Error('boom'));
    renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('trend-chart')).not.toBeInTheDocument();
  });
});

describe('Reports — biggest spends', () => {
  const spend = (id: string, label: string, amount: number, date = '2026-08-14', category = 'Shopping') =>
    ({ id, date, label, category, amount });

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
  });

  it("lists the server's biggest purchases with their day and category, each opening that purchase in the ledger", async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({
      biggestSpends: [spend('a', 'Big Mart', 9000), spend('b', 'Electricity bill', 2500, '2026-08-06', 'Bills & Utilities')],
    }));
    renderPage();

    const list = await screen.findByTestId('biggest-spends');
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    expect(within(list).getByText('Aug 14 · Shopping')).toBeInTheDocument();
    expect(within(list).getByText('₹9,000')).toBeInTheDocument();
    const href = within(list).getByRole('link', { name: /Big Mart, ₹9,000 on Aug 14/ }).getAttribute('href')!;
    const params = new URLSearchParams(href.split('?')[1]);
    expect(params.get('q')).toBe('Big Mart');
    expect(params.get('from')).toBe('2026-08-14');
    expect(params.get('to')).toBe('2026-08-14');
  });

  it('opens a purchase the server could not name on its day alone, with no search term', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ biggestSpends: [spend('a', 'Transaction', 900, '2026-08-09', 'Uncategorized')] }));
    renderPage();
    const href = (await screen.findByRole('link', { name: /Transaction, ₹900 on Aug 9/ })).getAttribute('href')!;
    const params = new URLSearchParams(href.split('?')[1]);
    expect(params.has('q')).toBe(false);
    expect(params.get('from')).toBe('2026-08-09');
    expect(params.get('to')).toBe('2026-08-09');
  });

  it('keeps a name with special characters intact in the link', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ biggestSpends: [spend('a', 'A & B + Co?', 500)] }));
    renderPage();
    const href = (await screen.findByRole('link', { name: /A & B \+ Co\?, ₹500/ })).getAttribute('href')!;
    expect(new URLSearchParams(href.split('?')[1]).get('q')).toBe('A & B + Co?');
  });

  it('is not drawn when there are none, or from an older server', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ biggestSpends: [] }));
    const { unmount } = renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Biggest spends')).not.toBeInTheDocument();
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());
    renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Biggest spends')).not.toBeInTheDocument();
  });
});

describe('Reports — daily spending', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-09']);
  });

  const withDays = (days: { date: string; amount: number }[], over: Partial<ReportData> = {}) =>
    report({ month: '2026-09', dailySpend: days, ...over });

  it('shades each day, names the biggest, and opens a day with spend in the ledger', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(withDays([{ date: '2026-09-14', amount: 3120 }, { date: '2026-09-03', amount: 400 }]));
    renderPage();

    expect(await screen.findByTestId('calendar-biggest')).toHaveTextContent('Biggest day: Sep 14, ₹3,120');
    const grid = screen.getByTestId('spend-calendar');
    expect(within(grid).getAllByRole('link')).toHaveLength(2);
    const href = within(grid).getByRole('link', { name: /Sep 14: ₹3,120 spent/ }).getAttribute('href')!;
    const params = new URLSearchParams(href.split('?')[1]);
    expect(params.get('from')).toBe('2026-09-14');
    expect(params.get('to')).toBe('2026-09-14');
    // 30 days in September, 2 with spend: the other 28 are plain, unlinked cells.
    expect(grid.querySelectorAll('div.aspect-square')).toHaveLength(28);
    // Said so that it is true in both themes: "darker" is wrong in dark mode, where heavier is lighter.
    expect(screen.getByText(/stronger shade means more spent/)).toBeInTheDocument();
    expect(screen.queryByText(/darker/i)).not.toBeInTheDocument();
  });

  it('says days past the data show nothing only for a month that is partly in', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(withDays([{ date: '2026-09-03', amount: 400 }], { complete: false }));
    const { unmount } = renderPage();
    expect(await screen.findByText(/Days past your latest data show nothing/)).toBeInTheDocument();
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(withDays([{ date: '2026-09-03', amount: 400 }], { complete: true }));
    renderPage();
    await screen.findByTestId('spend-calendar');
    expect(screen.queryByText(/Days past your latest data/)).not.toBeInTheDocument();
  });

  it('is not drawn for a month with no spending, or from an older server', async () => {
    vi.mocked(reportsApi.forMonth).mockResolvedValue(withDays([]));
    const { unmount } = renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('spend-calendar')).not.toBeInTheDocument();
    unmount();

    vi.mocked(reportsApi.forMonth).mockResolvedValue(report({ month: '2026-09' }));
    renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('spend-calendar')).not.toBeInTheDocument();
  });
});

describe('Reports — top merchants', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());
  });

  it('shows a locked preview with no real data, and never asks the paid endpoint, without Advanced Reports', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue({ features: { ADVANCED_REPORTS: false } } as never);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([{ merchantId: 'm1', merchantName: 'Secret Merchant', totalSpend: 99999, transactionCount: 3 }]);
    renderPage();

    expect(await screen.findByTestId('locked-merchants-bars')).toBeInTheDocument();
    expect(screen.getByText('Plus & Premium')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View plans/ })).toHaveAttribute('href', '/app/billing');
    expect(analyticsApi.topMerchants).not.toHaveBeenCalled();
    expect(screen.queryByText('Secret Merchant')).not.toBeInTheDocument();
  });

  it('shows the real top merchants for the month, with a link to Advanced Reports, with it', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue({ features: { ADVANCED_REPORTS: true } } as never);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([
      { merchantId: 'm1', merchantName: 'Big Mart', totalSpend: 9000, transactionCount: 4 },
      { merchantId: 'm2', merchantName: 'Corner Cafe', totalSpend: 1200, transactionCount: 1 },
    ]);
    renderPage();

    const list = await screen.findByTestId('top-merchants');
    expect(within(list).getByText('Big Mart')).toBeInTheDocument();
    expect(within(list).getByText('4 transactions')).toBeInTheDocument();
    expect(within(list).getByText('1 transaction')).toBeInTheDocument();
    expect(analyticsApi.topMerchants).toHaveBeenCalledWith('2026-08');
    expect(screen.getByRole('link', { name: 'See more in Advanced Reports' })).toHaveAttribute('href', '/app/reports/advanced');
    expect(screen.queryByTestId('locked-merchants-bars')).not.toBeInTheDocument();
  });

  it('shows at most five, and nothing when the month has none or the request fails', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue({ features: { ADVANCED_REPORTS: true } } as never);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue(
      Array.from({ length: 8 }, (_, i) => ({ merchantId: `m${i}`, merchantName: `Merchant ${i}`, totalSpend: 1000 - i, transactionCount: 1 })));
    const first = renderPage();
    const list = await screen.findByTestId('top-merchants');
    expect(within(list).getAllByRole('listitem')).toHaveLength(5);
    first.unmount();

    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
    const second = renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Top merchants')).not.toBeInTheDocument();
    second.unmount();

    vi.mocked(analyticsApi.topMerchants).mockRejectedValue(new Error('boom'));
    renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByText('Top merchants')).not.toBeInTheDocument();
  });

  it('shows nothing, rather than the locked preview, while the entitlement is still unknown', async () => {
    vi.mocked(entitlementsApi.mine).mockReturnValue(new Promise(() => {}));
    renderPage();
    await screen.findByTestId('category-rows');
    expect(screen.queryByTestId('locked-merchants-bars')).not.toBeInTheDocument();
    expect(screen.queryByTestId('top-merchants')).not.toBeInTheDocument();
  });
});
