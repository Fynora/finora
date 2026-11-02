import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react-native';
import { accountsApi, type AccountCoverage } from '../../api/endpoints';
import type { Account } from '../../types';
import { YourStatements } from './YourStatements';

// The real hook resolves once per test process, so the first test in a run saw the glass card
// remount its content mid-test and an element it had just found leave the tree.
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));
jest.mock('../../api/endpoints', () => ({
  ...jest.requireActual('../../api/endpoints'),
  accountsApi: { coverage: jest.fn() },
}));

const coverageMock = accountsApi.coverage as jest.Mock;
const TODAY = '2026-11-02';

function account(over: Partial<Account>): Account {
  return {
    id: 'a1',
    name: 'Sample Bank Savings',
    accountType: 'SAVINGS',
    balance: 0,
    bank: {} as Account['bank'],
    lastImportedAt: '2026-10-05T10:00:00Z',
    lastStatementPeriodStart: null,
    lastStatementPeriodEnd: null,
    statementsCount: 2,
    transactionsCount: 10,
    status: 'ACTIVE',
    primarySource: 'MANUAL',
    ...over,
  } as Account;
}

function coverage(segments: [string, string][], gaps: [string, string][] = []): AccountCoverage {
  return {
    accountId: 'x', coverageStatus: 'OK', coveredDays: 0, missingDays: 0, coveragePercentage: null,
    hasGaps: gaps.length > 0, hasOverlaps: false, hasNonStandardPeriods: false, hasDuplicatePeriods: false,
    segments: segments.map(([periodStart, periodEnd]) => ({ statementImportId: 's', periodStart, periodEnd, classification: 'STANDARD' })),
    gaps: gaps.map(([gapStart, gapEnd]) => ({ gapStart, gapEnd, daysMissing: 1, delta: null })),
    overlaps: [],
  };
}

const SAVINGS_JUN_SEP = coverage([
  ['2026-06-01', '2026-06-30'], ['2026-07-01', '2026-07-31'], ['2026-08-01', '2026-08-31'], ['2026-09-01', '2026-09-30'],
]);

function renderCard(accounts: Account[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <YourStatements accounts={accounts} today={TODAY} />
    </QueryClientProvider>,
  );
}

beforeEach(() => coverageMock.mockReset());

describe('YourStatements', () => {
  it('lists qualifying accounts, newest import first', async () => {
    coverageMock.mockResolvedValue(SAVINGS_JUN_SEP);
    renderCard([
      account({ id: 'old', name: 'Sample Bank Savings', lastImportedAt: '2026-09-01T10:00:00Z' }),
      account({ id: 'new', name: 'Sample Card', lastImportedAt: '2026-10-20T10:00:00Z' }),
      account({ id: 'aa', name: 'Linked Account', primarySource: 'ACCOUNT_AGGREGATOR' }),
      account({ id: 'none', name: 'Cash Wallet', statementsCount: 0 }),
    ]);
    expect(screen.getByText('Your statements')).toBeOnTheScreen();
    const names = screen.getAllByTestId('statement-months-name').map((n) => n.props.children);
    expect(names).toEqual(['Sample Card', 'Sample Bank Savings']);
    expect(screen.queryByText('Linked Account')).toBeNull();
    expect(screen.queryByText('Cash Wallet')).toBeNull();
    expect(coverageMock).toHaveBeenCalledTimes(2);
  });

  it('shows chips and the next line', async () => {
    coverageMock.mockResolvedValue(SAVINGS_JUN_SEP);
    renderCard([account({ accountNumberMasked: '••0001' })]);
    expect(await screen.findByText("Next: October's statement")).toBeOnTheScreen();
    for (const m of ['Jun', 'Jul', 'Aug', 'Sep', 'Oct']) expect(screen.getByText(m)).toBeOnTheScreen();
    expect(screen.getByLabelText('June, in Fynora')).toBeOnTheScreen();
    expect(screen.getByLabelText('October, next')).toBeOnTheScreen();
    expect(screen.getByText('••0001')).toBeOnTheScreen();
  });

  it('labels a missing month', async () => {
    coverageMock.mockResolvedValue(coverage(
      [['2026-07-01', '2026-07-31'], ['2026-09-01', '2026-09-30']],
      [['2026-08-01', '2026-08-31']],
    ));
    renderCard([account({})]);
    expect(await screen.findByText("August's statement is missing")).toBeOnTheScreen();
    expect(screen.getByLabelText('August, missing')).toBeOnTheScreen();
  });

  it('a failed coverage request shows "Couldn\'t load months" on that card only', async () => {
    coverageMock.mockImplementation((id: string) =>
      id === 'bad' ? Promise.reject(new Error('boom')) : Promise.resolve(SAVINGS_JUN_SEP));
    renderCard([
      account({ id: 'bad', name: 'Sample Card', lastImportedAt: '2026-10-20T10:00:00Z' }),
      account({ id: 'good', name: 'Sample Bank Savings' }),
    ]);
    expect(await screen.findByText("Couldn't load months")).toBeOnTheScreen();
    expect(await screen.findByText("Next: October's statement")).toBeOnTheScreen();
    expect(screen.getAllByText("Couldn't load months")).toHaveLength(1);
  });

  it('while loading shows the name only', () => {
    coverageMock.mockReturnValue(new Promise(() => {}));
    renderCard([account({})]);
    expect(screen.getByText('Sample Bank Savings')).toBeOnTheScreen();
    expect(screen.queryByText('Jun')).toBeNull();
    expect(screen.queryByText("Couldn't load months")).toBeNull();
  });

  it('renders nothing when no account qualifies', () => {
    const { toJSON } = renderCard([account({ primarySource: 'ACCOUNT_AGGREGATOR' })]);
    expect(toJSON()).toBeNull();
    expect(coverageMock).not.toHaveBeenCalled();
  });
});
