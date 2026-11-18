import { fireEvent, render, screen, within } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { categoriesApi, transactionsApi, type NetWorthData } from '../../api/endpoints';
import { comingUp, summarizeHoldings } from '../../lib/investmentSummary';
import { light } from '../../theme/palette';
import type { Account, Transaction } from '../../types';
import { PortfolioCard } from './PortfolioCard';
import { ComingUpCard } from './ComingUpCard';
import { MoneyGoingInCard } from './MoneyGoingInCard';
import { HoldingsCard } from './HoldingsCard';
import { NetWorthCard } from './NetWorthCard';

jest.mock('../../api/endpoints', () => ({
  categoriesApi: { list: jest.fn() },
  transactionsApi: { search: jest.fn() },
}));

const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;
const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const TODAY = new Date(2026, 10, 18);
const money = (n: number) => (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');

function holding(over: Partial<Account>): Account {
  return { id: 'a', name: 'Holding', accountType: 'INVESTMENT', balance: 1000, primarySource: 'MANUAL', ...over } as Account;
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

function netWorth(over: Partial<NetWorthData> = {}): NetWorthData {
  return {
    totalAssets: 1840000, totalLiabilities: 220000, netWorth: 1620000,
    history: [{ date: '2026-10-01', netWorth: 1500000 }, { date: '2026-11-01', netWorth: 1620000 }],
    ...over,
  } as NetWorthData;
}

describe('PortfolioCard', () => {
  it('shows the total, the subtitle and one label for the mix', () => {
    render(<PortfolioCard summary={summarizeHoldings(SAMPLE)} subtitle="6 holdings · 76% of your assets" money={money} />);

    expect(screen.getByText('₹14,00,000')).toBeTruthy();
    expect(screen.getByText('6 holdings · 76% of your assets')).toBeTruthy();
    expect(screen.getByLabelText('Mutual funds 46%, Deposits 25%, Stocks 15%, PPF, EPF and NPS 14%')).toBeTruthy();
    expect(screen.getByText('₹6,40,000 · 46%')).toBeTruthy();
  });

  // At the largest text size "₹13,77,000" broke into "₹13,77," and "000" on an iPhone Air simulator.
  it('keeps the total on one line at large text', () => {
    render(<PortfolioCard summary={summarizeHoldings(SAMPLE)} subtitle={null} money={money} />);

    expect(screen.getByText('₹14,00,000').props.numberOfLines).toBe(1);
    expect(screen.getByText('₹14,00,000').props.adjustsFontSizeToFit).toBe(true);
  });

  it('draws no mix when the holdings total nothing', () => {
    render(<PortfolioCard summary={summarizeHoldings([holding({ balance: 0 })])} subtitle={null} money={money} />);

    expect(screen.queryByTestId('portfolio-mix')).toBeNull();
  });
});

describe('ComingUpCard', () => {
  it('renders nothing when nothing is coming up', () => {
    render(<ComingUpCard rows={[]} money={money} />);

    expect(screen.queryByText('Coming up')).toBeNull();
  });

  it('shows the next maturity and how a plan is going', () => {
    render(<ComingUpCard rows={comingUp(SAMPLE, TODAY)} money={money} />);

    expect(screen.getByText('Bank FD matures')).toBeTruthy();
    expect(screen.getByText('12 Jan 2027 · in 55 days')).toBeTruthy();
    expect(screen.getByText('₹3,21,600')).toBeTruthy();
    expect(screen.getByText('12 of 24 paid')).toBeTruthy();
    expect(screen.getByLabelText('Recurring deposit: 12 of 24 paid')).toBeTruthy();
  });
});

describe('MoneyGoingInCard', () => {
  function renderCard(onSeePayments = jest.fn()) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(<QueryClientProvider client={client}><MoneyGoingInCard today={TODAY} onSeePayments={onSeePayments} /></QueryClientProvider>);
    return onSeePayments;
  }

  beforeEach(() => {
    categories.list.mockReset().mockResolvedValue([{ id: 'cat-inv', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' }]);
    transactions.search.mockReset();
  });

  it('asks for 12 months of outflows, totals them and opens the payments', async () => {
    transactions.search.mockResolvedValue(page([txn('a', 100000), txn('b', 44000, '2026-09-05')]) as never);
    const onSeePayments = renderCard();

    expect(await screen.findByText('₹1,44,000')).toBeTruthy();
    expect(screen.getByText('₹1,44,000').props.numberOfLines).toBe(1);
    expect(screen.getByText('About ₹12,000 a month · 2 payments')).toBeTruthy();
    expect(transactions.search).toHaveBeenCalledWith(expect.objectContaining({
      categoryId: 'cat-inv', type: 'EXPENSE', dateFrom: '2025-12-01', dateTo: '2026-11-18',
    }));
    expect(screen.getByLabelText('Last 12 months: ₹1,44,000, about ₹12,000 a month')).toBeTruthy();

    fireEvent.press(screen.getByLabelText('See payments'));
    expect(onSeePayments).toHaveBeenCalledWith('cat-inv', '2025-12-01', '2026-11-18');
  });

  it('says At least when there are more payments than it reads', async () => {
    transactions.search.mockResolvedValue(page([txn('a', 1000)], 12) as never);
    renderCard();

    expect(await screen.findByText('At least ₹10,000')).toBeTruthy();
    expect(transactions.search).toHaveBeenCalledTimes(10);
  });

  it('says so when no payments are found', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderCard();

    expect(await screen.findByText('No SIPs or broker payments found in your statements yet.')).toBeTruthy();
  });

  it('a failed load is not reported as no payments', async () => {
    transactions.search.mockRejectedValue(new Error('down'));
    renderCard();

    expect(await screen.findByText('Could not load your investment payments.')).toBeTruthy();
    expect(screen.queryByText('No SIPs or broker payments found in your statements yet.')).toBeNull();
  });
});

describe('HoldingsCard', () => {
  it('groups holdings, with a share and terms on each row', () => {
    render(<HoldingsCard summary={summarizeHoldings(SAMPLE)} today={TODAY} money={money} onMore={jest.fn()} />);

    ['Mutual funds', 'Deposits', 'Stocks', 'PPF, EPF and NPS'].forEach((g) => expect(screen.getByText(g)).toBeTruthy());
    expect(screen.getByText('27% of portfolio')).toBeTruthy();
    expect(screen.getByText('21% · 7.2% a year · matures 12 Jan 2027 · ₹3,21,600 at maturity')).toBeTruthy();
    expect(screen.getByLabelText('Flexi cap fund: ₹3,80,000, 27% of your portfolio.')).toBeTruthy();
  });

  it('the three-dot button is its own control', () => {
    const onMore = jest.fn();
    render(<HoldingsCard summary={summarizeHoldings(SAMPLE)} today={TODAY} money={money} onMore={onMore} />);

    fireEvent.press(screen.getByLabelText('More actions for Index fund'));

    expect(onMore).toHaveBeenCalledWith(expect.objectContaining({ id: 'idx' }));
  });
});

describe('NetWorthCard', () => {
  it('shows net worth, assets and liabilities', () => {
    render(<NetWorthCard netWorth={netWorth()} failed={false} money={money} />);

    expect(screen.getByText('₹16,20,000').props.numberOfLines).toBe(1);
    expect(screen.getByText('₹18,40,000 assets · ₹2,20,000 liabilities')).toBeTruthy();
  });

  it('explains a trend with too few points', () => {
    render(<NetWorthCard netWorth={netWorth({ history: [] })} failed={false} money={money} />);

    expect(screen.getByText('Building your net worth trend. Check back in a day or two.')).toBeTruthy();
  });

  it('paints a negative net worth as a loss', () => {
    render(<NetWorthCard netWorth={netWorth({ netWorth: -5000 })} failed={false} money={money} />);

    expect(StyleSheet.flatten(screen.getByText('-₹5,000').props.style).color).toBe(light.dangerInk);
  });

  it('says when a card is left out of the figures', () => {
    render(<NetWorthCard netWorth={netWorth({ cardsWithUnknownAmountOwed: 1 })} failed={false} money={money} />);

    expect(screen.getByText(/Leaves out 1 card/)).toBeTruthy();
  });

  it('prints no figure when net worth failed to load', () => {
    render(<NetWorthCard netWorth={null} failed money={money} />);

    const card = screen.getByTestId('net-worth-card');
    expect(within(card).getByText('Could not load your net worth.')).toBeTruthy();
    expect(within(card).queryByText(/₹/)).toBeNull();
  });
});
