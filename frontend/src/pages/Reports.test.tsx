import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Reports from './Reports';
import { reportsApi, type ReportData } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  reportsApi: { availableMonths: vi.fn(), forMonth: vi.fn() },
}));

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
  return render(
    <MemoryRouter>
      <Reports />
    </MemoryRouter>
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
