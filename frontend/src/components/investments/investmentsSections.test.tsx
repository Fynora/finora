import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '../../context/ThemeContext';
import { categoriesApi, transactionsApi, type NetWorthData } from '../../api/endpoints';
import type { Account, Transaction } from '../../types';
import { comingUp, summarizeHoldings } from '../../lib/investmentSummary';
import { PortfolioCard } from './PortfolioCard';
import { ComingUpCard } from './ComingUpCard';
import { MoneyGoingInCard } from './MoneyGoingInCard';
import { HoldingsCard } from './HoldingsCard';
import { NetWorthCard } from './NetWorthCard';

const trend = vi.hoisted(() => ({ options: null as unknown }));
vi.mock('react-chartjs-2', () => ({
  Line: ({ options }: { options: unknown }) => {
    trend.options = options;
    return <div data-testid="trend-chart" />;
  },
}));
vi.mock('../../api/endpoints', () => ({
  categoriesApi: { list: vi.fn() },
  transactionsApi: { search: vi.fn() },
}));

const TODAY = new Date(2026, 10, 18);
const money = (n: number) => (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');

function holding(overrides: Partial<Account>): Account {
  return { id: 'a', name: 'Holding', accountType: 'INVESTMENT', balance: 1000, primarySource: 'MANUAL', ...overrides } as Account;
}

const SAMPLE = [
  holding({ id: 'flx', name: 'Flexi cap fund', balance: 380000, investmentKind: 'Mutual Fund' }),
  holding({ id: 'idx', name: 'Index fund', balance: 260000, investmentKind: 'Mutual Fund' }),
  holding({ id: 'fd', name: 'Bank FD', balance: 300000, investmentKind: 'FD', interestRate: 7.2, maturityDate: '2027-01-12', maturityAmount: 321600 }),
  holding({ id: 'rd', name: 'Recurring deposit', balance: 48000, investmentKind: 'RD', installmentsPaid: 12, installmentsTotal: 24 }),
  holding({ id: 'brk', name: 'Broker account', balance: 210000, investmentKind: 'Stocks' }),
  holding({ id: 'ppf', name: 'PPF', balance: 202000, investmentKind: 'PPF/NPS' }),
];

function txn(id: string, amount: number, date = '2026-08-05'): Transaction {
  return { id, description: `SIP ${id}`, date, amount, type: 'EXPENSE', reconciliationStatus: 'INVESTMENT_TRANSFER' } as Transaction;
}

function page(content: Transaction[], totalPages = 1) {
  return { content, page: 0, size: 100, totalElements: content.length, totalPages };
}

function netWorth(overrides: Partial<NetWorthData> = {}): NetWorthData {
  return {
    totalAssets: 1840000, totalLiabilities: 220000, netWorth: 1620000,
    history: [{ date: '2026-10-01', netWorth: 1500000 }, { date: '2026-11-01', netWorth: 1620000 }],
    ...overrides,
  } as NetWorthData;
}

describe('PortfolioCard', () => {
  it('shows the total and describes the mix in one label', () => {
    render(<PortfolioCard summary={summarizeHoldings(SAMPLE)} money={money} />);

    expect(screen.getByText('₹14,00,000')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Mutual funds 46%, Deposits 25%, Stocks 15%, PPF, EPF and NPS 14%' })).toBeInTheDocument();
    expect(screen.getByText('₹6,40,000 · 46%')).toBeInTheDocument();
  });

  it('draws no mix bar when the holdings total nothing', () => {
    render(<PortfolioCard summary={summarizeHoldings([holding({ balance: 0 })])} money={money} />);

    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});

describe('ComingUpCard', () => {
  it('renders nothing when nothing is coming up', () => {
    const { container } = render(<ComingUpCard rows={[]} money={money} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('shows the next maturity and how a plan is going', () => {
    render(<ComingUpCard rows={comingUp(SAMPLE, TODAY)} money={money} />);

    expect(screen.getByText('Bank FD matures')).toBeInTheDocument();
    expect(screen.getByText('12 Jan 2027 · in 55 days')).toBeInTheDocument();
    expect(screen.getByText('₹3,21,600')).toBeInTheDocument();
    expect(screen.getByText('12 of 24 paid')).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: 'Recurring deposit: 12 of 24 paid' })).toHaveAttribute('aria-valuenow', '50');
  });

  it('lets a long unbroken name wrap inside the card', () => {
    render(<ComingUpCard rows={comingUp(SAMPLE, TODAY)} money={money} />);

    expect(screen.getByText('Bank FD matures')).toHaveClass('break-words');
    expect(screen.getByText('Recurring deposit')).toHaveClass('break-words');
  });
});

describe('MoneyGoingInCard', () => {
  const renderCard = () => render(<MemoryRouter><MoneyGoingInCard today={TODAY} /></MemoryRouter>);

  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(categoriesApi.list).mockResolvedValue([{ id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' }]);
  });

  it('asks for 12 months of outflows and totals them', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([txn('a', 100000), txn('b', 44000, '2026-09-05')]));
    renderCard();

    expect(await screen.findByText('₹1,44,000')).toBeInTheDocument();
    expect(screen.getByText('About ₹12,000 a month · 2 payments')).toBeInTheDocument();
    expect(transactionsApi.search).toHaveBeenCalledWith(expect.objectContaining({
      categoryId: 'cat-inv', type: 'EXPENSE', dateFrom: '2025-12-01', dateTo: '2026-11-18',
    }));
    expect(screen.getByRole('img', { name: 'Last 12 months: ₹1,44,000, about ₹12,000 a month' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'See payments' })).toHaveAttribute(
      'href', '/app/transactions?category=Investments&from=2025-12-01&to=2026-11-18',
    );
  });

  it('says one payment, not one payments', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([txn('a', 12000)]));
    renderCard();

    expect(await screen.findByText('About ₹1,000 a month · 1 payment')).toBeInTheDocument();
  });

  it('says At least when there are more payments than it reads', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([txn('a', 1000)], 12));
    renderCard();

    expect(await screen.findByText('At least ₹10,000')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Last 12 months: At least ₹10,000, about ₹833 a month' })).toBeInTheDocument();
    expect(transactionsApi.search).toHaveBeenCalledTimes(10);
  });

  it('says so when no payments are found', async () => {
    vi.mocked(transactionsApi.search).mockResolvedValue(page([]));
    renderCard();

    expect(await screen.findByText('No SIPs or broker payments found in your statements yet.')).toBeInTheDocument();
  });

  it('a failed load is not reported as no payments', async () => {
    vi.mocked(transactionsApi.search).mockRejectedValue(new Error('down'));
    renderCard();

    expect(await screen.findByText('Could not load your investment payments.')).toBeInTheDocument();
    expect(screen.queryByText('No SIPs or broker payments found in your statements yet.')).not.toBeInTheDocument();
  });
});

describe('HoldingsCard', () => {
  const renderCard = (holdings: Account[] = SAMPLE, handlers = { onUpdate: vi.fn(), onDelete: vi.fn() }) => {
    render(<HoldingsCard summary={summarizeHoldings(holdings)} today={TODAY} money={money} busyId={null} {...handlers} />);
    return handlers;
  };

  it('groups holdings and shows each one\'s share and terms', () => {
    renderCard();

    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual([
      'Mutual funds', 'Deposits', 'Stocks', 'PPF, EPF and NPS',
    ]);
    const row = screen.getByRole('group', { name: /^Bank FD/ });
    expect(within(row).getByText('21%')).toBeInTheDocument();
    expect(within(row).getByText('7.2% a year · matures 12 Jan 2027 · ₹3,21,600 at maturity')).toBeInTheDocument();
    expect(within(row).getByText('₹3,00,000')).toBeInTheDocument();
  });

  // The server accepts a negative balance. A negative width is invalid CSS, so the bar drew full.
  it('draws an empty share bar for a holding with a negative value', () => {
    renderCard([holding({ id: 'up', name: 'Up', balance: 1000 }), holding({ id: 'down', name: 'Down', balance: -200 })]);

    expect(within(screen.getByRole('group', { name: /^Down/ })).getByTestId('holding-share-bar')).toHaveStyle({ width: '0%' });
    expect(within(screen.getByRole('group', { name: /^Up/ })).getByTestId('holding-share-bar')).toHaveStyle({ width: '100%' });
  });

  it('the row menu offers Update value and Delete holding', async () => {
    const { onUpdate, onDelete } = renderCard();

    await userEvent.click(screen.getByRole('button', { name: 'More actions for Index fund' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Update value' }));
    expect(onUpdate).toHaveBeenCalledWith(expect.objectContaining({ id: 'idx' }));

    await userEvent.click(screen.getByRole('button', { name: 'More actions for Index fund' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Delete holding' }));
    expect(onDelete).toHaveBeenCalledWith(expect.objectContaining({ id: 'idx' }));
  });

  it('does not offer Update value on a holding whose value comes from a bank link', async () => {
    renderCard([holding({ id: 'aa', name: 'Linked FD', primarySource: 'ACCOUNT_AGGREGATOR' })]);

    await userEvent.click(screen.getByRole('button', { name: 'More actions for Linked FD' }));

    expect(screen.queryByRole('menuitem', { name: 'Update value' })).not.toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Delete holding' })).toBeInTheDocument();
  });
});

describe('NetWorthCard', () => {
  const renderCard = (props: Partial<React.ComponentProps<typeof NetWorthCard>> = {}) => render(
    <ThemeProvider><NetWorthCard netWorth={netWorth()} failed={false} money={money} {...props} /></ThemeProvider>,
  );

  it('shows net worth, assets, liabilities and the trend', () => {
    renderCard();

    expect(screen.getByText('₹16,20,000')).toBeInTheDocument();
    expect(screen.getByText('₹18,40,000 assets · ₹2,20,000 liabilities')).toBeInTheDocument();
    expect(screen.getByTestId('trend-chart')).toBeInTheDocument();
  });

  // The line draws no points, so a tooltip that needs the pointer exactly on one never opened.
  it('shows the date and amount anywhere along the trend', () => {
    renderCard();

    const options = trend.options as {
      interaction: { mode: string; intersect: boolean };
      plugins: { tooltip: { callbacks: { title: (items: { label: string }[]) => string; label: (item: { parsed: { y: number } }) => string } } };
    };
    expect(options.interaction).toEqual({ mode: 'index', intersect: false });
    expect(options.plugins.tooltip.callbacks.title([{ label: '2026-09-01' }])).toBe('1 Sep 2026');
    expect(options.plugins.tooltip.callbacks.label({ parsed: { y: 1620000 } })).toBe('₹16,20,000');
  });

  it('explains a trend with too few points', () => {
    renderCard({ netWorth: netWorth({ history: [] }) });

    expect(screen.getByText('Building your net worth trend. Check back in a day or two.')).toBeInTheDocument();
    expect(screen.queryByTestId('trend-chart')).not.toBeInTheDocument();
  });

  it('paints a negative net worth as a loss', () => {
    renderCard({ netWorth: netWorth({ netWorth: -5000 }) });

    expect(screen.getByText('-₹5,000')).toHaveClass('text-danger');
  });

  it('says when a card is left out of the figures', () => {
    renderCard({ netWorth: netWorth({ cardsWithUnknownAmountOwed: 1 }) });

    expect(screen.getByText("Leaves out 1 card whose amount owed isn't known.")).toBeInTheDocument();
  });

  it('prints no figure when net worth failed to load', () => {
    renderCard({ netWorth: null, failed: true });

    expect(screen.getByText('Could not load your net worth.')).toBeInTheDocument();
    expect(screen.queryByText(/₹/)).not.toBeInTheDocument();
  });
});
