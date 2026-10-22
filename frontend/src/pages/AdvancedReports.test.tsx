import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AdvancedReports from './AdvancedReports';
import { analyticsApi, entitlementsApi } from '../api/endpoints';
import type { AdvancedReport, EntitlementsDto } from '../api/endpoints';
import { ThemeProvider } from '../context/ThemeContext';
import { downloadBlob } from '../lib/download';
import { fixtureMonth, fixtureReport } from './advancedReports/testFixtures';

vi.mock('../api/endpoints', () => ({
  entitlementsApi: { mine: vi.fn() },
  analyticsApi: { advanced: vi.fn(), categoryConfidence: vi.fn(), learningGrowth: vi.fn() },
}));
vi.mock('../lib/download', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/download')>()),
  downloadBlob: vi.fn(),
}));

const PLUS: EntitlementsDto = { planCode: 'PLUS', planName: 'Plus', features: { ADVANCED_REPORTS: true } };
const FREE: EntitlementsDto = { planCode: 'FREE', planName: 'Free', features: {} };

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

function renderPage(path = '/app/reports/advanced') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <MemoryRouter initialEntries={[path]}>
          <AdvancedReports />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

const lastCall = () => {
  const calls = vi.mocked(analyticsApi.advanced).mock.calls;
  return calls[calls.length - 1][0];
};

describe('AdvancedReports', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Like the server, answer with the range that was asked for.
    vi.mocked(analyticsApi.advanced).mockImplementation(async ({ range }) => {
      const base = fixtureReport();
      return fixtureReport({ period: { ...base.period, range } });
    });
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([{ category: 'Food', avgConfidence: 88, merchantCount: 4 }]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([{ month: '2026-08', learnedCount: 10, correctedCount: 2 }]);
  });

  describe('entitlement', () => {
    it('always shows the page title, even before the entitlement check resolves', () => {
      vi.mocked(entitlementsApi.mine).mockReturnValue(pending());
      renderPage();
      expect(screen.getByRole('heading', { name: /Advanced Reports/ })).toBeInTheDocument();
    });

    it('shows the upgrade prompt, not the report, for a Free-plan user, and asks the server for nothing', async () => {
      vi.mocked(entitlementsApi.mine).mockResolvedValue(FREE);
      renderPage();

      expect(await screen.findByText(/Plus & Premium feature/)).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /View plans/ })).toHaveAttribute('href', '/app/billing');
      expect(analyticsApi.advanced).not.toHaveBeenCalled();
      expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    });

    it('shows the upgrade prompt for a user with no subscription at all (fails closed)', async () => {
      vi.mocked(entitlementsApi.mine).mockResolvedValue({ planCode: null, planName: null, features: {} });
      renderPage();
      expect(await screen.findByText(/Plus & Premium feature/)).toBeInTheDocument();
      expect(analyticsApi.advanced).not.toHaveBeenCalled();
    });
  });

  describe('for an entitled user', () => {
    beforeEach(() => vi.mocked(entitlementsApi.mine).mockResolvedValue(PLUS));

    it('asks for the last 12 months by default and shows the server figures with their changes', async () => {
      renderPage();

      expect(await screen.findByTestId('kpi-income')).toHaveTextContent('₹6,00,000');
      expect(lastCall()).toEqual({ range: 'LAST_12_MONTHS' });
      expect(screen.getByTestId('kpi-expense')).toHaveTextContent('₹2,40,000');
      expect(screen.getByTestId('kpi-net')).toHaveTextContent('₹3,60,000');
      expect(screen.getByTestId('kpi-average')).toHaveTextContent('₹40,000');
      expect(screen.getByTestId('kpi-savings')).toHaveTextContent('60%');
      const kpis = screen.getByTestId('kpi-row');
      expect(within(kpis).getByText('↑ 11%')).toBeInTheDocument();
      // Expenses and average monthly spend both rose 20%: two lines, each with its own arrow.
      expect(within(kpis).getAllByText('↑ 20%')).toHaveLength(2);
      expect(within(kpis).getByText('↓ 3 pp')).toBeInTheDocument();
      expect(within(kpis).getAllByText(/vs the previous 6 months/).length).toBeGreaterThan(0);
    });

    it('names the months in the period and what it is compared with', async () => {
      renderPage();
      const notice = await screen.findByTestId('period-notice');
      expect(notice).toHaveTextContent('Apr 2026 – Sep 2026');
      expect(notice).toHaveTextContent('6 months');
      expect(notice).toHaveTextContent('Compared with Oct 2025 – Mar 2026.');
    });

    it('asks again when the period changes, and keeps the choice in the address', async () => {
      renderPage();
      await screen.findByTestId('kpi-income');

      fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'LAST_3_MONTHS' } });

      await waitFor(() => expect(lastCall()).toEqual({ range: 'LAST_3_MONTHS' }));
    });

    it('opens on the period and tab named in the address', async () => {
      renderPage('/app/reports/advanced?range=THIS_YEAR&tab=income');
      await screen.findByTestId('income-sources');
      expect(lastCall()).toEqual({ range: 'THIS_YEAR' });
      expect(screen.getByLabelText('Period')).toHaveValue('THIS_YEAR');
      expect(screen.getByRole('tab', { name: 'Income' })).toHaveAttribute('aria-selected', 'true');
    });

    it('ignores a range or tab it does not know rather than asking the server for it', async () => {
      renderPage('/app/reports/advanced?range=LAST_WEEK&tab=nonsense&start=junk');
      await screen.findByTestId('kpi-income');
      expect(lastCall()).toEqual({ range: 'LAST_12_MONTHS' });
      expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true');
    });

    it('offers a custom run only with both months, starting from the months on screen', async () => {
      renderPage();
      await screen.findByTestId('kpi-income');

      fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'CUSTOM' } });

      expect(await screen.findByLabelText('From')).toHaveValue('2026-04');
      expect(screen.getByLabelText('To')).toHaveValue('2026-09');
      await waitFor(() => expect(lastCall()).toEqual({ range: 'CUSTOM', startMonth: '2026-04', endMonth: '2026-09' }));
    });

    it('lets a custom month be picked from the months the history has, in any browser', async () => {
      renderPage('/app/reports/advanced?range=CUSTOM&start=2026-04&end=2026-09');
      await screen.findByTestId('kpi-income');
      const from = screen.getByLabelText('From');
      expect(within(from).getAllByRole('option').map((o) => o.getAttribute('value')).slice(0, 3)).toEqual(['2026-09', '2026-08', '2026-07']);
      const options = within(from).getAllByRole('option');
      expect(options[options.length - 1]).toHaveValue('2025-01');

      fireEvent.change(from, { target: { value: '2025-06' } });

      await waitFor(() => expect(lastCall()).toEqual({ range: 'CUSTOM', startMonth: '2025-06', endMonth: '2026-09' }));
    });

    it('does not show the old period\'s months or ways to compare beside a period still loading', async () => {
      renderPage();
      await screen.findByLabelText('Compare with');
      expect(screen.getByTestId('period-span')).toHaveTextContent('Apr 2026 – Sep 2026');
      vi.mocked(analyticsApi.advanced).mockReturnValue(pending());

      fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'LAST_3_MONTHS' } });

      await waitFor(() => expect(lastCall()).toEqual({ range: 'LAST_3_MONTHS' }));
      expect(screen.queryByTestId('period-span')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Compare with')).not.toBeInTheDocument();
      expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-busy', 'true');
    });

    it('asks nothing for a custom run that has no months yet, and says what it needs', async () => {
      renderPage('/app/reports/advanced?range=CUSTOM');
      expect(await screen.findByText('Choose a start and an end month')).toBeInTheDocument();
      expect(analyticsApi.advanced).not.toHaveBeenCalled();
    });

    it('does not ask for a custom run whose start is after its end', async () => {
      renderPage('/app/reports/advanced?range=CUSTOM&start=2026-09&end=2026-01');
      expect(await screen.findByText('Choose a start and an end month')).toBeInTheDocument();
      expect(analyticsApi.advanced).not.toHaveBeenCalled();
    });

    it('offers the compare picker only when there is more than one way to compare', async () => {
      renderPage();
      const compare = await screen.findByLabelText('Compare with');
      expect(within(compare).getAllByRole('option').map((o) => o.textContent)).toEqual(['Previous 6 months', 'Same months a year earlier']);

      fireEvent.change(compare, { target: { value: 'SAME_PERIOD_LAST_YEAR' } });
      await waitFor(() => expect(lastCall()).toEqual({ range: 'LAST_12_MONTHS', compare: 'SAME_PERIOD_LAST_YEAR' }));
    });

    it('starts the comparison from its default again when the period changes', async () => {
      renderPage();
      fireEvent.change(await screen.findByLabelText('Compare with'), { target: { value: 'SAME_PERIOD_LAST_YEAR' } });
      await waitFor(() => expect(lastCall()).toEqual({ range: 'LAST_12_MONTHS', compare: 'SAME_PERIOD_LAST_YEAR' }));

      fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'LAST_3_MONTHS' } });

      await waitFor(() => expect(lastCall()).toEqual({ range: 'LAST_3_MONTHS' }));
    });

    it('never shows the old period\'s figures under a new period when the new request fails', async () => {
      renderPage();
      await screen.findByTestId('kpi-income');
      vi.mocked(analyticsApi.advanced).mockRejectedValueOnce(new Error('boom'));

      fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'LAST_3_MONTHS' } });

      expect(await screen.findByText(/Couldn’t load Advanced Reports/)).toBeInTheDocument();
      expect(screen.queryByTestId('kpi-income')).not.toBeInTheDocument();
    });

    it('draws a long period by year even after a short one was on screen', async () => {
      renderPage();
      await screen.findByTestId('cash-flow-chart');
      const months = Array.from({ length: 30 }, (_, i) => fixtureMonth(`${2024 + Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, '0')}`));
      const base = fixtureReport();
      vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({ months, period: { ...base.period, range: 'ALL_TIME', monthCount: 30, startMonth: '2024-01', endMonth: '2026-06', compareOptions: [], compare: null } }));

      fireEvent.change(screen.getByLabelText('Period'), { target: { value: 'ALL_TIME' } });

      expect(await screen.findByText('Each bar is a calendar year of the period.')).toBeInTheDocument();
    });

    it('has no compare picker when the period has one comparison, or none', async () => {
      const base = fixtureReport();
      vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({ period: { ...base.period, compareOptions: ['PREVIOUS_PERIOD'] } }));
      renderPage();
      await screen.findByTestId('kpi-income');
      expect(screen.queryByLabelText('Compare with')).not.toBeInTheDocument();
    });

    it('shows no change lines, and says once why, when the server withheld the comparison', async () => {
      const base = fixtureReport();
      vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({
        comparison: { ...base.comparison, reliable: false, gateReason: 'PRIOR_PERIOD_BEFORE_HISTORY', incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null, avgMonthlyExpenseDeltaPct: null, savingsRateDeltaPp: null },
      }));
      renderPage();

      const kpis = await screen.findByTestId('kpi-row');
      expect(within(kpis).queryByText(/vs the previous/)).not.toBeInTheDocument();
      expect(screen.getByTestId('period-notice')).toHaveTextContent('does not reach back far enough');
      expect(screen.getByTestId('kpi-income')).toHaveTextContent('₹6,00,000');
    });

    it('says which month was left out, and why', async () => {
      const base = fixtureReport();
      vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({
        period: { ...base.period, leftOutMonth: '2026-10', leftOutReason: 'PARTIAL_MONTH' },
      }));
      renderPage();
      expect(await screen.findByTestId('period-notice')).toHaveTextContent('Oct 2026 is left out');
    });

    it('withholds the savings rate with its reason instead of showing a number', async () => {
      const base = fixtureReport();
      vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({
        totals: { ...base.totals, savingsRatePct: null, savingsRateGateReason: 'UNRESOLVED_EXCEEDS_INCOME' },
      }));
      renderPage();
      const card = await screen.findByTestId('kpi-savings');
      expect(card).toHaveTextContent('—');
      expect(card.parentElement).toHaveTextContent('more credits are unclassified');
    });

    describe('when there is nothing to show', () => {
      const empty = (reason: string): AdvancedReport => {
        const base = fixtureReport();
        return fixtureReport({ period: { ...base.period, hasData: false, noDataReason: reason, startMonth: null, endMonth: null, monthCount: 0 }, months: [], insights: [] });
      };

      it('invites a new user to import, with the button', async () => {
        vi.mocked(analyticsApi.advanced).mockResolvedValue(empty('NO_TRANSACTIONS'));
        renderPage();
        expect(await screen.findByText('No transactions yet')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'Import Statement' })).toHaveAttribute('href', '/app/import');
        expect(screen.getByRole('button', { name: 'Export CSV' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Print / PDF' })).toBeDisabled();
      });

      it('explains that a first month still in progress is not yet a whole month', async () => {
        vi.mocked(analyticsApi.advanced).mockResolvedValue(empty('ONLY_PARTIAL_MONTH'));
        renderPage();
        expect(await screen.findByText('Your first month is still in progress')).toBeInTheDocument();
      });

      it('says a period the history does not reach is empty, rather than drawing zeros', async () => {
        vi.mocked(analyticsApi.advanced).mockResolvedValue(empty('NO_MONTHS_IN_PERIOD'));
        renderPage();
        expect(await screen.findByText('No data in this period')).toBeInTheDocument();
        expect(screen.queryByTestId('kpi-row')).not.toBeInTheDocument();
      });
    });

    it('offers to try again when the first load fails', async () => {
      vi.mocked(analyticsApi.advanced).mockRejectedValueOnce(new Error('boom'));
      renderPage();
      expect(await screen.findByText(/Couldn’t load Advanced Reports/)).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

      expect(await screen.findByTestId('kpi-income')).toBeInTheDocument();
      expect(analyticsApi.advanced).toHaveBeenCalledTimes(2);
    });

    describe('tabs', () => {
      it('shows the spending tab: categories with change, payment methods and the trend', async () => {
        renderPage('/app/reports/advanced?tab=spending');
        const rows = await screen.findByTestId('category-rows');
        expect(within(rows).getByText('Food & Dining')).toBeInTheDocument();
        expect(screen.getByTestId('payment-methods')).toBeInTheDocument();
        expect(screen.getByTestId('category-trend-chart')).toBeInTheDocument();
        expect(screen.getByText('No international spend')).toBeInTheDocument();
      });

      it('shows the merchants tab with how much of the spending names a merchant', async () => {
        renderPage('/app/reports/advanced?tab=merchants');
        expect(await screen.findByTestId('merchant-rows')).toHaveTextContent('Amazon');
        expect(screen.getByTestId('merchant-coverage')).toHaveTextContent('62% of your spending is tied to a named merchant');
      });

      it('moves between tabs from the keyboard', async () => {
        renderPage();
        await screen.findByTestId('kpi-income');
        const overview = screen.getByRole('tab', { name: 'Overview' });
        overview.focus();

        fireEvent.keyDown(overview, { key: 'ArrowRight' });

        await waitFor(() => expect(screen.getByRole('tab', { name: 'Spending' })).toHaveAttribute('aria-selected', 'true'));
        expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'advanced-tab-spending');
      });

      it('takes the period pickers away on the categorisation tab and says it does not follow them', async () => {
        renderPage('/app/reports/advanced?tab=categorisation');

        expect(await screen.findByTestId('categorisation-intro')).toHaveTextContent('does not change with the period');
        expect(screen.queryByLabelText('Period')).not.toBeInTheDocument();
        expect(await screen.findByTestId('confidence-rows')).toHaveTextContent('Across 4 merchants');
        expect(screen.getByTestId('learning-rows')).toHaveTextContent('10 learned · 2 corrected');
        // The period report is not asked for while only the diagnostics are on screen.
        expect(analyticsApi.advanced).not.toHaveBeenCalled();
      });

      it('shows empty categorisation cards for a user who has taught Fynora nothing', async () => {
        vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
        vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);
        renderPage('/app/reports/advanced?tab=categorisation');
        expect(await screen.findByText('Nothing learned yet')).toBeInTheDocument();
        expect(screen.getByText('No learning history yet')).toBeInTheDocument();
      });
    });

    describe('drill-down', () => {
      it('links a category to the Ledger for the whole period, naming the category', async () => {
        renderPage('/app/reports/advanced?tab=spending');
        const link = await screen.findByRole('link', { name: 'Food & Dining' });
        expect(link).toHaveAttribute('href', '/app/transactions?from=2026-04-01&to=2026-09-30&category=Food+%26+Dining');
      });

      it('never filters the Ledger by "Uncategorized", which is no category', async () => {
        const base = fixtureReport();
        vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({
          categories: { ...base.categories, rows: [{ ...base.categories.rows[0], name: 'Uncategorized' }] },
        }));
        renderPage('/app/reports/advanced?tab=spending');
        const link = await screen.findByRole('link', { name: 'Uncategorized' });
        expect(link).toHaveAttribute('href', '/app/transactions?from=2026-04-01&to=2026-09-30');
      });

      it('links a merchant to the Ledger search for its name over the period', async () => {
        renderPage('/app/reports/advanced?tab=merchants');
        const link = await screen.findByRole('link', { name: 'Amazon' });
        expect(link).toHaveAttribute('href', '/app/transactions?from=2026-04-01&to=2026-09-30&q=Amazon');
      });

      it('links an insight to the transactions behind it', async () => {
        renderPage();
        const list = await screen.findByTestId('insights');
        const links = within(list).getAllByRole('link', { name: 'See the transactions' });
        expect(links[1]).toHaveAttribute('href', '/app/transactions?from=2026-04-01&to=2026-09-30&category=Shopping');
      });
    });

    describe('chart', () => {
      it('has the same figures as a table, one click away', async () => {
        renderPage();
        await screen.findByTestId('cash-flow-chart');

        fireEvent.click(screen.getAllByRole('button', { name: /View as table/ })[0]);

        const table = await screen.findByTestId('cash-flow-table');
        expect(within(table).getByRole('row', { name: /Apr 2026/ })).toHaveTextContent('₹1,00,000');
        expect(within(table).getByRole('row', { name: /Apr 2026/ })).toHaveTextContent('60%');
        expect(screen.queryByTestId('cash-flow-chart')).not.toBeInTheDocument();
      });

      it('marks a month that is only part of a month, in the chart and in words', async () => {
        const base = fixtureReport();
        vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({
          months: base.months.map((m) => (m.month === '2026-05' ? fixtureMonth('2026-05', { complete: false }) : m)),
          period: { ...base.period, incompleteMonths: ['2026-05'] },
        }));
        renderPage();
        expect(await screen.findByTestId('flow-partial-note')).toHaveTextContent('Only part of the month is in');
        expect(screen.getByText(/May 2026 is only part of a month/)).toBeInTheDocument();
        expect(within(screen.getByTestId('flow-2026-05')).getByText('May*')).toBeInTheDocument();
      });

      it('draws a period longer than two years one calendar year at a time, and says so', async () => {
        const months = Array.from({ length: 30 }, (_, i) => {
          const y = 2024 + Math.floor(i / 12);
          return fixtureMonth(`${y}-${String((i % 12) + 1).padStart(2, '0')}`);
        });
        const base = fixtureReport();
        vi.mocked(analyticsApi.advanced).mockResolvedValue(fixtureReport({ months, period: { ...base.period, monthCount: 30, startMonth: '2024-01', endMonth: '2026-06' } }));
        renderPage();
        await screen.findByTestId('cash-flow-chart');
        expect(screen.getByText('Each bar is a calendar year of the period.')).toBeInTheDocument();
        expect(screen.getByTestId('flow-2024')).toBeInTheDocument();
        expect(screen.getByTestId('flow-2026')).toBeInTheDocument();
        // 2026 has only six months in the period: drawn as the part it has, and marked.
        expect(screen.getByTestId('flow-partial-note')).toHaveTextContent('Only part of the year is in the period');

        fireEvent.click(screen.getByRole('button', { name: 'monthly' }));
        expect(await screen.findByTestId('flow-2024-03')).toBeInTheDocument();
      });
    });

    describe('export', () => {
      it('writes the period as a CSV, named for its months', async () => {
        renderPage();
        await screen.findByTestId('kpi-income');

        fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));

        expect(downloadBlob).toHaveBeenCalledTimes(1);
        const [blob, name] = vi.mocked(downloadBlob).mock.calls[0];
        expect(name).toBe('fynora-advanced-report-2026-04-to-2026-09.csv');
        expect(blob.type).toBe('text/csv');
      });

      it('prints the whole report, not just the open tab, and tidies up afterwards', async () => {
        document.title = 'Fynora';
        const seen: { parent: Element | null; text: string; title: string; bodyMarked: boolean }[] = [];
        const print = vi.spyOn(window, 'print').mockImplementation(() => {
          const sheet = document.querySelector('.fy-ar-print-root');
          seen.push({
            parent: sheet?.parentElement ?? null, text: sheet?.textContent ?? '', title: document.title,
            bodyMarked: document.body.classList.contains('fy-ar-printing'),
          });
        });
        renderPage();
        await screen.findByTestId('kpi-income');

        fireEvent.click(screen.getByRole('button', { name: 'Print / PDF' }));

        await waitFor(() => expect(print).toHaveBeenCalledTimes(1));
        // A direct child of <body>: the print stylesheet hides every other child of <body>.
        expect(seen[0].parent).toBe(document.body);
        // Sections from tabs that are not open are on the page too.
        expect(seen[0].text).toContain('Apr 2026 – Sep 2026');
        expect(seen[0].text).toContain('Who you paid most');
        expect(seen[0].text).toContain('Where income came from');
        // The browser offers the document title as the PDF's file name.
        expect(seen[0].title).toBe('fynora-advanced-report-2026-04-to-2026-09');
        // The print rules that hide the app only apply while <body> is marked: every other page of the
        // app (Reports' own Print / PDF, a plain Ctrl+P) prints itself as before.
        expect(seen[0].bodyMarked).toBe(true);

        window.dispatchEvent(new Event('afterprint'));
        await waitFor(() => expect(document.querySelector('.fy-ar-print-root')).toBeNull());
        expect(document.title).toBe('Fynora');
        expect(document.body.classList.contains('fy-ar-printing')).toBe(false);
        print.mockRestore();
      });

      it('leaves nothing behind when the page goes away mid-print', async () => {
        document.title = 'Fynora';
        const print = vi.spyOn(window, 'print').mockImplementation(() => {});
        const { unmount } = renderPage();
        await screen.findByTestId('kpi-income');
        fireEvent.click(screen.getByRole('button', { name: 'Print / PDF' }));
        await waitFor(() => expect(print).toHaveBeenCalledTimes(1));

        unmount();

        expect(document.body.classList.contains('fy-ar-printing')).toBe(false);
        expect(document.title).toBe('Fynora');
        expect(document.querySelector('.fy-ar-print-root')).toBeNull();
        print.mockRestore();
      });

      it('puts the real title back even when a browser never says the first print finished', async () => {
        document.title = 'Fynora';
        const print = vi.spyOn(window, 'print').mockImplementation(() => {});
        renderPage();
        await screen.findByTestId('kpi-income');

        // No afterprint between the two prints.
        fireEvent.click(screen.getByRole('button', { name: 'Print / PDF' }));
        fireEvent.click(screen.getByRole('button', { name: 'Print / PDF' }));
        await waitFor(() => expect(print).toHaveBeenCalledTimes(2));

        window.dispatchEvent(new Event('afterprint'));
        await waitFor(() => expect(document.querySelector('.fy-ar-print-root')).toBeNull());
        expect(document.title).toBe('Fynora');
        expect(document.body.classList.contains('fy-ar-printing')).toBe(false);
        print.mockRestore();
      });
    });
  });
});
